# DIYHUD 地图渲染选型调研（2026-10-28）

> 背景：diyhud v1 用高德栅格瓦片转单色白线，两个死结——①自定义样式在 JSAPI 1.4/2.0 均不生效（key 未开通自定义地图服务）；②栅格去标注三种像素方案全失败（文字与道路连通）。本文给出替代路线。

## 结论（一句话）

**换 MapLibre GL JS + OpenFreeMap 免费矢量瓦片**：无 key、样式全可改、标注是独立图层可整层删除，一次性解决上面两个死结，且顺带修掉一个尚未发现的坐标系偏差 bug。

## 推荐方案：MapLibre GL JS + OpenFreeMap

- **是什么**：MapLibre = Mapbox GL JS 最后的开源分叉（TypeScript + WebGL 渲染矢量瓦片）；OpenFreeMap = 免费托管的 OpenMapTiles 全球矢量瓦片，官方给三个现成样式：
  - `https://tiles.openfreemap.org/styles/positron`（极简灰白，做 HUD 白线底最合适）
  - `https://tiles.openfreemap.org/styles/bright` / `styles/liberty`
- **自定义样式**：用 Maputnik 编辑器改（官方明确支持"remove labels, POIs, or change colors"），改完的 style JSON 自己托管（放 `diyhud/` 由现有 tile_server.py 一并 serve 即可）。做法：拿 positron 底 → 所有 fill/line 层改白色、背景黑 → 删光 symbol 层 = 永久无标注，不再需要像素剥离。
- **旋转**：MapLibre 原生 `map.setBearing()` 真矢量旋转，替掉现在 CSS/ctx 转 raster 的接缝+模糊 hack。
- **接入成本**：index.html 里地图区换成 `<div>` + 一个 ~200KB 的 maplibre-gl.js，删掉 `/t/` 瓦片代理链路；左/右文字区布局不动。
- **配额**：免费无 key；离线批量下载限 3000 tiles/50MB 每次（车机场景在线流式即可，Pi 有网；极端离线可自托管 planet pbf）。

## 顺带修一个隐藏 bug：GCJ-02 vs WGS-84

高德瓦片是 GCJ-02 火星坐标，Pi 的 NMEA GPS 输出是 WGS-84——现在把 GPS 点画在高德底图上，无锡地区实际偏 ~50-500m。OSM 数据就是 WGS-84，换 OpenFreeMap 后车标位置天然对齐，这个偏差自动消失。

## 备选（为什么不选）

- **MapTiler / Mapbox**：要 key + 免费额度有限，Mapbox GL JS 2.x 起闭源收费。
- **继续高德栅格**：自定义样式要开通付费的「自定义地图服务」；去标注已证伪。
- **maplibre-native 给 8155 车机 App 用**：以后 CastSink 内嵌地图可走这条路（同一套 style），暂不在本期。

## 已知短板（接受）

- OSM 国内路网覆盖无锡城区够用，但 POI 密度低于高德；无实时路况层（导航数据本来就走车机 nav_state.json，不依赖它）。
- 中文路名 OSM 有，字体渲染走内置 glyphs（`tiles.openfreemap.org/fonts/...`），style JSON 里保留即可。

## 落地步骤（预估 1-2h，可下张卡做）

1. Maputnik 打开 positron → 全层改白线黑底 → 删 symbol 层 → 导出 `diyhud/style-hud.json`
2. tile_server.py 加一条 serve 该 JSON（替代 `/t/` 瓦片路由）
3. index.html 地图区换 MapLibre：`new Map({style:'./style-hud.json', bearing:heading, center:[lon,lat]})`，150ms 轮询 state.json 改为更新 center/bearing
4. sim 模式验证（state.json 同 v1），截图对比 v1 观感
5. 满意后 v1 的 AMap 栅格路径保留为 `?m=amap` 回退开关

参考：openfreemap.org/quick_start · maplibre.org/maplibre-gl-js/docs · maputnik.github.io
