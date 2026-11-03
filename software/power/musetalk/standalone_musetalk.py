#!/usr/bin/env python3
"""
standalone_musetalk.py — 独立 MuseTalk 推理脚本 (绕过 ComfyUI 节点系统)

显存管控策略 (11GB RTX 2080 Ti):
  1. [模型隔离] Whisper 提取特征后立即卸载 (Node 1→3 之间)
  2. [分块切片] UNet 推理 batch_size=4, 不一次性喂全部帧 (Node 5)
  3. [边缘羽化] 融合时高斯模糊 mask, 消除像素断层 (Node 6)

7 节点管线:
  Node 1a: 加载 Whisper (临时)
  Node 1b: 加载核心推理模型 (VAE/UNet/DWPose/FaceParsing)
  Node 2:  人脸检测 + 预处理 (bbox + landmark + 裁剪)
  Node 3:  音频特征提取 (Whisper → encoder_embeddings → chunks)
  Node 4:  VAE 编码 (人脸帧 → latent)
  Node 5:  UNet 推理 (latent + audio → 新 latent), 分块
  Node 6:  VAE 解码 + 融合 (latent → 像素 + 羽化 blend)
  Node 7:  视频合成 (ffmpeg 帧序列 → .mp4)

用法:
  cd E:\\ComfyUI\\custom_nodes\\ComfyUI-MuseTalk_FSH\\
  python standalone_musetalk.py --face face.jpg --audio speech.wav
  python standalone_musetalk.py --face input.mp4 --audio speech.wav --output result.mp4

可选参数:
  --bbox-shift      Bbox 微调 (默认 0)
  --batch-size      UNet 批大小 (默认 4, 11GB 建议 4)
  --batch-size-fa   人脸检测批大小 (默认 2)
  --fps             输出帧率 (默认 25)
  --no-fp16         禁用 fp16 (默认启用)
  --no-feather      禁用边缘羽化
  --use-saved-coord 复用已保存的裁剪坐标
"""

import os
import sys
import gc
import argparse
import shutil
import subprocess
import pickle
import time
import math
import warnings
from typing import Optional, List, Tuple

import cv2
import numpy as np
import torch
from tqdm import tqdm

warnings.filterwarnings("ignore", category=UserWarning)

# ═══════════════════════════════════════════════════════════
# 路径 & 环境初始化
# ═══════════════════════════════════════════════════════════

# 自动定位 MuseTalk_FSH 根目录 (无论脚本在哪个子目录运行)
_script_path = os.path.dirname(os.path.abspath(__file__))
SCRIPT_DIR = _script_path
# 向上回溯, 直到找到 musetalk 包
while not os.path.exists(os.path.join(SCRIPT_DIR, "musetalk", "__init__.py")):
    parent = os.path.dirname(SCRIPT_DIR)
    if parent == SCRIPT_DIR:
        raise RuntimeError("无法找到 musetalk 包! 请确认脚本在 ComfyUI-MuseTalk_FSH 目录内。")
    SCRIPT_DIR = parent

MODELS_DIR = os.path.join(SCRIPT_DIR, "models")
DEFAULT_OUTPUT = os.path.join(SCRIPT_DIR, "..", "output")

# 确保可导入 musetalk 包
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

# ffmpeg 路径
FFMPEG_PATH = os.path.join(
    os.path.dirname(sys.executable), "ffmpeg", "ffmpeg.exe"
)
if not os.path.exists(FFMPEG_PATH):
    FFMPEG_PATH = "ffmpeg"  # fallback to PATH


def cuda_malloc_supported() -> bool:
    """绕过 ComfyUI 的 cuda_malloc stub, 直接检测 CUDA"""
    return torch.cuda.is_available()


# ═══════════════════════════════════════════════════════════
# 工具函数
# ═══════════════════════════════════════════════════════════

VRAM_PEAK = 0  # MB


def log_vram(tag: str = ""):
    """记录并追踪 VRAM 峰值"""
    global VRAM_PEAK
    if not torch.cuda.is_available():
        return
    allocated = torch.cuda.memory_allocated() // 1024 // 1024
    reserved = torch.cuda.memory_reserved() // 1024 // 1024
    VRAM_PEAK = max(VRAM_PEAK, allocated)
    print(f"  [VRAM] {tag}: 已分配={allocated}MB, 预留={reserved}MB, 峰值={VRAM_PEAK}MB")


def safe_cuda_empty():
    """安全清空 CUDA 缓存"""
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
        torch.cuda.synchronize()


def destroy_model(obj, name: str = "model"):
    """彻底销毁模型并释放显存"""
    if obj is not None:
        if hasattr(obj, 'to'):
            try:
                obj.to('cpu')
            except Exception:
                pass
        del obj
    safe_cuda_empty()
    log_vram(f"del {name}")


def get_file_type(path: str) -> str:
    _, ext = os.path.splitext(path)
    ext = ext.lower()
    if ext in ['.jpg', '.jpeg', '.png', '.bmp', '.tif', '.tiff']:
        return 'image'
    elif ext in ['.avi', '.mp4', '.mov', '.flv', '.mkv']:
        return 'video'
    else:
        return 'unsupported'


# ═══════════════════════════════════════════════════════════
# Node 1a: 加载 Whisper (仅用于音频特征提取)
# ═══════════════════════════════════════════════════════════

def load_whisper(models_dir: str, device: torch.device):
    """
    仅加载 Whisper 模型。
    ⚠️ 提取特征后必须卸载, 为 VAE/UNet 腾出显存。
    """
    from musetalk.whisper.audio2feature import Audio2Feature

    print("\n" + "=" * 60)
    print("[Node 1a] 加载 Whisper 音频处理器 (临时)")
    print("=" * 60)

    audio_processor = Audio2Feature(
        model_path=os.path.join(models_dir, "whisper", "tiny.pt")
    )

    # 强制 fp16 压榨显存
    audio_processor.model = audio_processor.model.half().to(device)
    log_vram("Whisper 加载完成")

    return audio_processor


# ═══════════════════════════════════════════════════════════
# Node 1b: 加载核心推理模型 (卸载 Whisper 后调用)
# ═══════════════════════════════════════════════════════════

def load_inference_models(
    models_dir: str,
    device: torch.device,
    use_fp16: bool = True,
):
    """
    加载 MuseTalk 核心模型:
      - FaceParsing (人脸解析, 用于边缘羽化)
      - DWPose (姿态检测, mmpose)
      - VAE (sd-vae-ft-mse)
      - UNet (musetalk latent diffusion)

    所有模型强制 fp16 入显存。
    """
    from musetalk.utils.face_parsing import FaceParsing
    # 注意: 我们不使用 load_all_model (它会额外加载一个 audio_processor)
    # 改为独立加载 VAE 和 UNet，避免浪费
    from musetalk.models.vae import VAE
    from musetalk.models.unet import UNet, PositionalEncoding
    from mmpose.apis import init_model

    print("\n" + "=" * 60)
    print("[Node 1b] 加载核心推理模型 (Whisper 已卸载)")
    print("=" * 60)

    # ── 1. FaceParsing (人脸解析) ──────────────────────
    print("  → 人脸解析模型 (FaceParsing)...")
    resnet_path = os.path.join(models_dir, "face-parse-bisent", "resnet18-5c106cde.pth")
    face_model_pth = os.path.join(models_dir, "face-parse-bisent", "79999_iter.pth")
    fp_model = FaceParsing(resnet_path, face_model_pth)
    if use_fp16:
        fp_model.model = fp_model.model.half()
    fp_model.model = fp_model.model.to(device)
    log_vram("FaceParsing")

    # ── 2. DWPose (mmpose) ─────────────────────────────
    print("  → DWPose 姿态检测 (mmpose)...")
    config_file = os.path.join(
        SCRIPT_DIR, "musetalk", "utils", "dwpose",
        "rtmpose-l_8xb32-270e_coco-ubody-wholebody-384x288.py"
    )
    checkpoint_file = os.path.join(models_dir, "dwpose", "dw-ll_ucoco_384.pth")
    dwpose_model = init_model(config_file, checkpoint_file, device=device)
    # mmpose 不直接支持 half() 转换，但模型小 (~200MB), 保持 fp32 可接受
    log_vram("DWPose")

    # ── 3. VAE ─────────────────────────────────────────
    print("  → VAE (sd-vae-ft-mse)...")
    vae = VAE(model_path=os.path.join(models_dir, "sd-vae-ft-mse/"))
    if use_fp16:
        vae.vae = vae.vae.half()
    vae.vae = vae.vae.to(device)
    log_vram("VAE")

    # ── 4. UNet + PE ───────────────────────────────────
    print("  → UNet (musetalk)...")
    unet_config = os.path.join(models_dir, "musetalk", "musetalk.json")
    unet_model_path = os.path.join(models_dir, "musetalk", "pytorch_model.bin")
    unet = UNet(unet_config=unet_config, model_path=unet_model_path)
    if use_fp16:
        unet.model = unet.model.half()
    unet.model = unet.model.to(device)
    pe = PositionalEncoding(d_model=384).to(device)
    log_vram("UNet")

    print(f"\n  ✅ 核心模型全部加载完成, 总显存: {VRAM_PEAK}MB")
    return fp_model, dwpose_model, vae, unet, pe


# ═══════════════════════════════════════════════════════════
# Node 2: 人脸检测 + 预处理
# ═══════════════════════════════════════════════════════════

def node2_preprocess_face(
    dwpose_model,
    video_path: str,
    batch_size_fa: int,
    bbox_shift: int,
    fps_target: int = 25,
    target_frame_count: int = None,
) -> Tuple[List[np.ndarray], List[tuple], int]:
    """
    Node 2: 人脸检测 + 关键点提取 + 裁剪

    对输入的图片/视频进行 DWPose + S3FD 人脸检测,
    返回裁剪后的人脸帧和坐标。

    关键修正: 静态图片输入时, 自动按 target_frame_count 复制帧,
    使帧数与音频特征块数对齐。

    Args:
        target_frame_count: 目标帧数(来自音频块数), 图片输入时使用

    Returns:
      cropped_frames : List[np.ndarray] 裁剪后的人脸帧 (H,W,3)
      coord_list     : List[tuple] 每帧的 bbox (x1,y1,x2,y2)
      frame_list     : List[np.ndarray] 原始完整帧 (BGR)
      actual_fps     : int 实际帧率
    """
    from musetalk.utils.preprocessing import (
        get_landmark_and_bbox, read_imgs, coord_placeholder
    )

    print("\n" + "=" * 60)
    print("[Node 2] 人脸检测 + 预处理")
    print("=" * 60)

    file_type = get_file_type(video_path)
    is_image = (file_type == 'image')

    if is_image:
        print(f"  → 输入类型: 静态图片 ({video_path})")
        img_list = [video_path]
        actual_fps = fps_target
    elif file_type == 'video':
        print(f"  → 输入类型: 视频 ({video_path})")
        # 用 OpenCV 提取帧到内存 (不写磁盘, 减少 I/O)
        cap = cv2.VideoCapture(video_path)
        actual_fps = cap.get(cv2.CAP_PROP_FPS)
        total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        print(f"  → 视频: {total_frames} 帧, {actual_fps:.2f} FPS")

        # 保存到临时目录以便 preprocessing 模块处理
        frame_dir = os.path.join(SCRIPT_DIR, ".frames_temp")
        if os.path.exists(frame_dir):
            shutil.rmtree(frame_dir)
        os.makedirs(frame_dir, exist_ok=True)

        img_list = []
        frame_idx = 0
        pbar = tqdm(total=total_frames, desc="  提取帧")
        while True:
            ret, frame = cap.read()
            if not ret:
                break
            fname = os.path.join(frame_dir, f"frame_{frame_idx:08d}.png")
            cv2.imwrite(fname, frame)
            img_list.append(fname)
            frame_idx += 1
            pbar.update(1)
        cap.release()
        pbar.close()
        actual_fps = int(actual_fps)
        print(f"  ✓ 提取 {len(img_list)} 帧完成")
    else:
        raise ValueError(f"不支持的输入类型: {video_path}")

    # ── 人脸检测 + 关键点定位 ────────────────────
    print("  → 执行人脸检测 + 关键点定位...")
    coord_list, frame_list = get_landmark_and_bbox(
        dwpose_model, img_list, batch_size_fa, bbox_shift
    )
    face_detected = sum(1 for c in coord_list if c != coord_placeholder)
    print(f"  ✓ 检测完成: {len(coord_list)} 帧, 检测到人脸: {face_detected}")

    if face_detected == 0:
        raise RuntimeError("未检测到人脸! 请检查输入图片/视频。")

    # ── 裁剪人脸区域 + 智能 padding ──────────────
    print("  → 裁剪人脸区域...")
    cropped_frames = []
    for i, frame in enumerate(tqdm(frame_list, desc="  裁剪")):
        if coord_list[i] == coord_placeholder:
            cropped_frames.append(None)
            continue
        x1, y1, x2, y2 = coord_list[i]
        margin = int((x2 - x1) * 0.05)
        x1 = max(0, x1 - margin)
        y1 = max(0, y1 - margin)
        x2 = min(frame.shape[1], x2 + margin)
        y2 = min(frame.shape[0], y2 + margin)
        cropped = frame[y1:y2, x1:x2]
        cropped_frames.append(cropped)

    # 清理临时文件
    if file_type == 'video':
        shutil.rmtree(frame_dir, ignore_errors=True)

    # ── 关键修复: 静态图片 → 复制帧对齐音频块数 ──
    if is_image and target_frame_count is not None:
        orig_count = len(cropped_frames)
        print(f"  → 图片输入: 复制 {orig_count} 帧为 {target_frame_count} 帧")
        # 复制所有列表
        single_cropped = cropped_frames[0]
        single_coord = coord_list[0]
        single_frame = frame_list[0]
        cropped_frames = [single_cropped] * target_frame_count
        coord_list = [single_coord] * target_frame_count
        frame_list = [single_frame] * target_frame_count
        print(f"  ✓ 帧数已扩展: {len(cropped_frames)}")

    actual_fps = fps_target if is_image else actual_fps
    print(f"  ✓ 预处理完成: {len(cropped_frames)} 帧, 输出 FPS={actual_fps}")

    return cropped_frames, coord_list, frame_list, actual_fps


# ═══════════════════════════════════════════════════════════
# Node 3: 音频特征提取
# ═══════════════════════════════════════════════════════════

def node3_extract_audio_features(
    audio_processor,
    audio_path: str,
    total_frames: int,
    fps: int,
) -> List[np.ndarray]:
    """
    Node 3: 音频特征提取

    1. Whisper transcribe → encoder_embeddings
    2. 按 fps 分块 → 每个视频帧对应一个 (50, 384) 特征块
    3. ⚠️ 完成后必须卸载 Whisper

    Returns:
      whisper_chunks : List[np.ndarray], 长度=total_frames, 每个形状 (50, 384)
    """
    print("\n" + "=" * 60)
    print("[Node 3] 音频特征提取 (Whisper)")
    print("=" * 60)

    print(f"  → 音频文件: {audio_path}")
    print(f"  → 目标帧数: {total_frames}, FPS: {fps}")

    # Whisper 推理 → encoder_embeddings
    print("  → Whisper transcribe...")
    audio_feature = audio_processor.audio2feat(audio_path)
    print(f"  ✓ 原始特征: {audio_feature.shape} (frames @ 50Hz, 384-dim)")

    # 按帧率分块
    print("  → 分块为视频帧对齐的特征...")
    whisper_chunks = audio_processor.feature2chunks(
        audio_feature, fps, audio_feat_length=[2, 2]
    )
    print(f"  ✓ 特征块数: {len(whisper_chunks)}")

    # 如果特征块少于视频帧数, padding 最后一帧
    while len(whisper_chunks) < total_frames:
        whisper_chunks.append(whisper_chunks[-1])

    # 如果多于, 截断
    whisper_chunks = whisper_chunks[:total_frames]

    print(f"  ✓ 最终特征块: {len(whisper_chunks)} 个, 每个形状 {whisper_chunks[0].shape}")

    # ⚠️ 提取完毕, 卸载 Whisper (腾出 ~500MB 显存)
    log_vram("Whisper 卸载前")
    print("  → 卸载 Whisper 模型...")
    destroy_model(audio_processor, "Whisper")
    print("  ✓ Whisper 已卸载, 显存已释放")

    return whisper_chunks


# ═══════════════════════════════════════════════════════════
# Node 4: VAE 编码 (人脸帧 → latent)
# ═══════════════════════════════════════════════════════════

def node4_vae_encode(
    vae,
    cropped_frames: List[np.ndarray],
    coord_list: List[tuple],
    use_fp16: bool = True,
) -> List[Optional[torch.Tensor]]:
    """
    Node 4: VAE 编码

    将裁剪后的人脸帧编码为潜在空间 latent。
    未检测到人脸的位置填充 None。

    Returns:
      vae_latents : List[Optional[torch.Tensor]], 每个形状 (1, 4, 32, 32)
    """
    from musetalk.utils.preprocessing import coord_placeholder

    print("\n" + "=" * 60)
    print("[Node 4] VAE 编码 (人脸帧 → latent)")
    print("=" * 60)

    vae_latents = []
    pbar = tqdm(total=len(cropped_frames), desc="  VAE 编码")
    for i, (frame, coord) in enumerate(zip(cropped_frames, coord_list)):
        if coord == coord_placeholder or frame is None:
            vae_latents.append(None)
            pbar.update(1)
            continue

        # 预处理: resize → 归一化 → tensor
        resized = cv2.resize(frame, (vae._resized_img, vae._resized_img),
                             interpolation=cv2.INTER_LANCZOS4)
        x = np.asarray(resized) / 255.0
        x = np.transpose(x, (2, 0, 1))  # HWC → CHW
        x = torch.FloatTensor(x).unsqueeze(0).to(vae.vae.device)  # [1,3,256,256]

        # 归一化: mean=0.5, std=0.5
        x = vae.transform(x)

        # VAE 编码
        with torch.no_grad():
            if use_fp16:
                x = x.half()
            latent = vae.vae.encode(x).latent_dist.sample()
            latent = latent * vae.scaling_factor

        vae_latents.append(latent)  # [1, 4, 32, 32]
        pbar.update(1)

    pbar.close()
    valid_count = sum(1 for l in vae_latents if l is not None)
    print(f"  ✓ 编码完成: {valid_count}/{len(vae_latents)} 个有效 latent")
    log_vram("VAE 编码后")

    return vae_latents


# ═══════════════════════════════════════════════════════════
# Node 5: UNet 推理 (核心, 分块切片)
# ═══════════════════════════════════════════════════════════

def node5_unet_inference(
    unet,
    pe,
    vae_latents: List[Optional[torch.Tensor]],
    whisper_chunks: List[np.ndarray],
    batch_size: int = 4,
    use_fp16: bool = True,
) -> List[Optional[torch.Tensor]]:
    """
    Node 5: UNet 推理 (分块切片)

    核心约束:
      ⚠️ 硬编码 batch_size 限制, 防止 OOM
      ⚠️ 每个 chunk 独立的 `with torch.no_grad()`
      ⚠️ 多 chunk 之间不保留中间张量

    策略:
      - 从 vae_latents 中提取有效帧索引
      - 按 batch_size 分组推理
      - 每批: latent_batch + audio_feature_batch → UNet → pred_latent_batch

    Returns:
      pred_latents : List[Optional[torch.Tensor]], 与输入对齐的预测 latent 列表
    """
    print("\n" + "=" * 60)
    print("[Node 5] UNet 推理 (分块切片)")
    print("=" * 60)
    print(f"  Batch size: {batch_size} (硬编码, 防 OOM)")
    print(f"  总帧数: {len(vae_latents)}")

    # ── 构建有效帧索引 ─────────────────────────────
    valid_indices = [i for i, l in enumerate(vae_latents) if l is not None]
    total_valid = len(valid_indices)
    print(f"  有效帧: {total_valid}")

    # ── 组合有效帧的 latent 列表 ────────────────────
    valid_latents = [vae_latents[i] for i in valid_indices]

    # ── 分块推理 ───────────────────────────────────
    pred_latents = [None] * len(vae_latents)  # 占位
    timesteps = torch.tensor([0], device=unet.model.device)

    num_batches = math.ceil(total_valid / batch_size)
    pbar = tqdm(total=num_batches, desc="  UNet 推理")

    for batch_idx in range(num_batches):
        start = batch_idx * batch_size
        end = min(start + batch_size, total_valid)
        batch_valid_indices = valid_indices[start:end]

        # 构建 latent batch
        latent_batch = torch.cat(
            [valid_latents[i] for i in range(start, end)], dim=0
        )  # [B, 4, 32, 32]

        # 构建 audio feature batch
        audio_feature_batch = np.stack(
            [whisper_chunks[idx] for idx in batch_valid_indices], axis=0
        )  # [B, 50, 384]
        audio_feature_batch = torch.FloatTensor(audio_feature_batch).to(unet.model.device)
        audio_feature_batch = pe(audio_feature_batch)

        if use_fp16:
            latent_batch = latent_batch.half()
            audio_feature_batch = audio_feature_batch.half()

        # UNet 推理
        with torch.no_grad():
            pred = unet.model(
                latent_batch,
                timesteps,
                encoder_hidden_states=audio_feature_batch,
            ).sample

        # 写回
        for j, orig_idx in enumerate(batch_valid_indices):
            pred_latents[orig_idx] = pred[j].unsqueeze(0)  # [1, 4, 32, 32]

        # 每批清理中间张量
        del latent_batch, audio_feature_batch, pred
        if batch_idx % 4 == 3:  # 每 4 批强制清理一次
            safe_cuda_empty()

        pbar.update(1)

    pbar.close()
    log_vram("UNet 推理完成")
    print(f"  ✓ UNet 推理完成: {total_valid} 帧生成")

    return pred_latents


# ═══════════════════════════════════════════════════════════
# Node 6: VAE 解码 + 融合 (含边缘羽化)
# ═══════════════════════════════════════════════════════════

def node6_decode_and_blend(
    fp_model,
    vae,
    pred_latents: List[Optional[torch.Tensor]],
    frame_list: List[np.ndarray],
    coord_list: List[tuple],
    use_fp16: bool = True,
    feather: bool = True,
) -> List[np.ndarray]:
    """
    Node 6: VAE 解码 + 融合

    1. VAE 解码 latent → 像素空间人脸帧
    2. 人脸解析 mask 分割精确面部区域
    3. 高斯模糊 mask (边缘羽化), 消除像素断层
    4. 贴图融合回原始帧

    退路: 如果显存紧张, mask 运算通过 CPU 执行。
    """
    from musetalk.utils.blending import get_image as original_blend
    from musetalk.utils.preprocessing import coord_placeholder
    from musetalk.utils.face_parsing import FaceParsing

    print("\n" + "=" * 60)
    print("[Node 6] VAE 解码 + 融合 (边缘羽化)")
    print("=" * 60)

    result_frames = []

    pbar = tqdm(total=len(pred_latents), desc="  解码+融合")
    for i, (latent, frame, coord) in enumerate(
        zip(pred_latents, frame_list, coord_list)
    ):
        if latent is None or coord == coord_placeholder:
            # 未检测到人脸 → 保留原始帧
            result_frames.append(frame)
            pbar.update(1)
            continue

        x1, y1, x2, y2 = coord

        # ── VAE 解码 ──────────────────────────────
        with torch.no_grad():
            if use_fp16:
                latent = latent.half()
            decoded = vae.vae.decode(latent / vae.scaling_factor).sample
            # decoded: [1, 3, 256, 256], range ≈ [-1, 1]

        # 后处理: tensor → numpy uint8
        decoded = decoded.squeeze(0).cpu().numpy()  # [3, 256, 256]
        decoded = np.transpose(decoded, (1, 2, 0))  # [256, 256, 3]
        decoded = ((decoded + 1.0) * 127.5).clip(0, 255).astype(np.uint8)

        # ── 融合: 原始 blend + 边缘羽化 ──────────
        try:
            combine_frame = original_blend(fp_model, frame, decoded, coord)

            if feather:
                # 边缘羽化: 用 OpenCV 高斯模糊 mask 消除像素断层
                # 策略: 在已 blend 的区域外围做渐变过渡
                h, w = combine_frame.shape[:2]
                face_resized = cv2.resize(decoded, (x2 - x1, y2 - y1))

                # 构建羽化 mask: 中心区域=1, 边缘渐变到0
                feather_mask = np.zeros((h, w), dtype=np.float32)
                # blend 区域向内收缩 10px 作为纯面部区
                inner = 10
                x1_f = max(0, x1 + inner)
                y1_f = max(0, y1 + inner)
                x2_f = min(w, x2 - inner)
                y2_f = min(h, y2 - inner)
                if x2_f > x1_f and y2_f > y1_f:
                    feather_mask[y1_f:y2_f, x1_f:x2_f] = 1.0

                # 高斯模糊: sigma 与 blend 区域大小成正比
                sigma = max(3.0, min(15.0, (x2 - x1) * 0.03))
                feather_mask = cv2.GaussianBlur(feather_mask, (0, 0), sigmaX=sigma)
                feather_mask_3c = np.stack([feather_mask] * 3, axis=2)

                # 羽化融合
                roi = combine_frame.astype(np.float32)
                # 把 decode 的人脸覆盖到对应区域
                combine_frame_f = frame.copy().astype(np.float32)
                combine_frame_f[y1:y2, x1:x2] = face_resized.astype(np.float32)

                # blend: roi * (1 - mask) + face * mask
                blend = roi * (1 - feather_mask_3c) + combine_frame_f * feather_mask_3c
                combine_frame = blend.clip(0, 255).astype(np.uint8)

        except Exception as e:
            print(f"  ⚠️ 融合失败 (帧 {i}): {type(e).__name__}, 回退到直接贴图")
            combine_frame = frame.copy()
            face_resized = cv2.resize(decoded, (x2 - x1, y2 - y1))
            combine_frame[y1:y2, x1:x2] = face_resized

        result_frames.append(combine_frame)
        pbar.update(1)

    pbar.close()
    log_vram("解码+融合完成")
    print(f"  ✓ 解码融合完成: {len(result_frames)} 帧")

    return result_frames


# ═══════════════════════════════════════════════════════════
# Node 7: 视频合成
# ═══════════════════════════════════════════════════════════

def node7_export_video(
    result_frames: List[np.ndarray],
    output_path: str,
    audio_path: Optional[str],
    fps: int,
):
    """
    Node 7: 视频合成

    1. 帧序列 → temp.mp4 (libx264)
    2. 合并音频 → final.mp4
    3. 清理临时文件
    """
    print("\n" + "=" * 60)
    print("[Node 7] 视频合成")
    print("=" * 60)

    temp_video = output_path + ".temp.mp4"

    print(f"  → 输出路径: {output_path}")
    print(f"  → 帧数: {len(result_frames)}, FPS: {fps}")

    # ── 写为视频 ─────────────────────────────────
    if len(result_frames) == 0:
        raise ValueError("没有帧可合成!")

    h, w = result_frames[0].shape[:2]
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(temp_video, fourcc, fps, (w, h))

    for frame in tqdm(result_frames, desc="  写入视频帧"):
        writer.write(cv2.cvtColor(frame, cv2.COLOR_RGB2BGR))
    writer.release()
    print(f"  ✓ 临时视频写入完成")

    # ── 合并音频 ─────────────────────────────────
    if audio_path and os.path.exists(audio_path):
        print("  → 合并音频...")
        cmd = [
            FFMPEG_PATH, "-y", "-v", "fatal",
            "-i", temp_video,
            "-i", audio_path,
            "-c:v", "libx264", "-crf", "18",
            "-c:a", "aac", "-b:a", "192k",
            "-shortest",
            output_path
        ]
        subprocess.run(cmd, check=True)
        os.remove(temp_video)
        print(f"  ✓ 音频合并完成")
    else:
        os.rename(temp_video, output_path)

    print(f"\n  ✅ 输出文件: {output_path}")
    file_size = os.path.getsize(output_path) / 1024 / 1024
    print(f"  📦 文件大小: {file_size:.1f} MB")


# ═══════════════════════════════════════════════════════════
# 主流程
# ═══════════════════════════════════════════════════════════

def parse_args():
    parser = argparse.ArgumentParser(
        description="MuseTalk 独立推理脚本 (绕开 ComfyUI 节点)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument("--face", required=True, help="人脸图片/视频路径")
    parser.add_argument("--audio", required=True, help="音频文件路径")
    parser.add_argument("--output", default=None, help="输出视频路径 (默认: auto)")
    parser.add_argument("--bbox-shift", type=int, default=0, help="Bbox 微调偏移 (默认 0)")
    parser.add_argument("--batch-size", type=int, default=4, help="UNet 批大小 (默认 4)")
    parser.add_argument("--batch-size-fa", type=int, default=2, help="人脸检测批大小 (默认 2)")
    parser.add_argument("--fps", type=int, default=25, help="输出帧率 (默认 25)")
    parser.add_argument("--no-fp16", action="store_true", help="禁用 fp16 (默认启用)")
    parser.add_argument("--no-feather", action="store_true", help="禁用边缘羽化")
    parser.add_argument("--use-saved-coord", action="store_true", help="复用已有裁剪坐标")
    parser.add_argument("--auto", action="store_true", help="自动模式(不暂停等待输入)")
    return parser.parse_args()


def main():
    args = parse_args()

    # ── 设备检测 ─────────────────────────────────
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    use_fp16 = not args.no_fp16 and device.type == "cuda"
    use_feather = not args.no_feather

    print(f"🚀 MuseTalk 独立推理引擎")
    print(f"   Device: {device}")
    print(f"   fp16:   {'✅' if use_fp16 else '❌'}")
    print(f"   Feather: {'✅' if use_feather else '❌'}")
    print(f"   Batch:  {args.batch_size}")
    print(f"   Face:   {args.face}")
    print(f"   Audio:  {args.audio}")

    # ── 输出路径 ─────────────────────────────────
    if args.output is None:
        face_basename = os.path.splitext(os.path.basename(args.face))[0]
        audio_basename = os.path.splitext(os.path.basename(args.audio))[0]
        out_dir = os.path.join(DEFAULT_OUTPUT, "musetalk_result")
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, f"{face_basename}_{audio_basename}.mp4")
    else:
        out_path = args.output
        os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)

    log_vram("初始状态")

    # ══════════════════════════════════════════════
    # Node 1a: 加载 Whisper (临时)
    # ══════════════════════════════════════════════
    audio_processor = load_whisper(MODELS_DIR, device)

    if not args.auto:
        print("\n📌 [Node 1a] Whisper 已加载. 按 Enter 进入 Node 3 (音频特征提取)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 3: 音频特征提取 (Whisper → 卸载 → 腾出显存)
    # ══════════════════════════════════════════════
    # 策略: 先用 Whisper 提取全部特征, 然后立即卸载它
    whisper_chunks = node3_extract_audio_features(
        audio_processor, args.audio,
        total_frames=99999,  # 提取全部, 后面再截断
        fps=args.fps
    )

    # Whisper 已卸载
    total_audio_frames = len(whisper_chunks)
    print(f"\n  ✅ Whisper 已卸载, 音频总特征块: {total_audio_frames}")
    log_vram("Whisper 卸载后")
    available_mb = (torch.cuda.get_device_properties(0).total_memory -
                    torch.cuda.memory_allocated()) // 1024 // 1024
    print(f"  可用显存: {available_mb} MB")

    if not args.auto:
        print("📌 [Node 3] 音频特征提取完成. 按 Enter 加载核心模型 (Node 1b)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 1b: 加载核心推理模型
    # ══════════════════════════════════════════════
    fp_model, dwpose_model, vae, unet, pe = load_inference_models(
        MODELS_DIR, device, use_fp16
    )

    if not args.auto:
        print("📌 [Node 1b] 核心模型已加载. 按 Enter 进入 Node 2 (人脸预处理)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 2: 人脸预处理 (静态图片会自动复制帧对齐音频)
    # ══════════════════════════════════════════════
    cropped_frames, coord_list, frame_list, actual_fps = node2_preprocess_face(
        dwpose_model, args.face,
        batch_size_fa=args.batch_size_fa,
        bbox_shift=args.bbox_shift,
        fps_target=args.fps,
        target_frame_count=total_audio_frames,  # 关键: 图片输入时复制帧
    )

    total_frames = len(cropped_frames)

    # 帧数对齐: whisper_chunks 必须与帧数一致
    if len(whisper_chunks) > total_frames:
        whisper_chunks = whisper_chunks[:total_frames]
    elif len(whisper_chunks) < total_frames:
        last = whisper_chunks[-1]
        whisper_chunks += [last] * (total_frames - len(whisper_chunks))

    print(f"\n  ✅ 帧数对齐: {total_frames} 帧 ↔ {len(whisper_chunks)} 特征块")

    if not args.auto:
        print("📌 [Node 2] 预处理完成. 按 Enter 进入 Node 4 (VAE 编码)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 4: VAE 编码
    # ══════════════════════════════════════════════
    vae_latents = node4_vae_encode(vae, cropped_frames, coord_list, use_fp16)

    if not args.auto:
        print("📌 [Node 4] VAE 编码完成. 按 Enter 进入 Node 5 (UNet 推理)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 5: UNet 推理 (分块切片)
    # ══════════════════════════════════════════════
    pred_latents = node5_unet_inference(
        unet, pe, vae_latents, whisper_chunks,
        batch_size=args.batch_size,
        use_fp16=use_fp16,
    )

    if not args.auto:
        print("📌 [Node 5] UNet 推理完成. 按 Enter 进入 Node 6 (解码+融合)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 6: VAE 解码 + 融合
    # ══════════════════════════════════════════════
    result_frames = node6_decode_and_blend(
        fp_model, vae, pred_latents, frame_list, coord_list,
        use_fp16=use_fp16,
        feather=use_feather,
    )

    if not args.auto:
        print("📌 [Node 6] 解码融合完成. 按 Enter 进入 Node 7 (视频合成)...", end="", flush=True)
        input()

    # ══════════════════════════════════════════════
    # Node 7: 视频合成
    # ══════════════════════════════════════════════
    node7_export_video(result_frames, out_path, args.audio, actual_fps or args.fps)

    # ── 清理 ────────────────────────────────────
    print("\n  清理模型...")
    destroy_model(fp_model, "FaceParsing")
    destroy_model(dwpose_model, "DWPose")
    destroy_model(vae, "VAE")
    destroy_model(unet, "UNet")
    safe_cuda_empty()

    print("\n" + "=" * 60)
    print(f"🎉 全部完成! 输出文件: {out_path}")
    print(f"   VRAM 峰值: {VRAM_PEAK} MB / "
          f"{torch.cuda.get_device_properties(0).total_memory // 1024 // 1024} MB")
    print("=" * 60)


if __name__ == "__main__":
    main()
