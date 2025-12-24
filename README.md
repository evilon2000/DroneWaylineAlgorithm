# Drone Path Planning Algorithm for Image Generation

## 项目概述

这是一个用于无人机（UAV）表面路径规划算法的Java实现，专为影像生成（例如航拍、3D建模或图像采集）设计。该算法支持生成优化飞行路径，确保无人机高效覆盖目标区域，支持断点续航、相机校准和路径管理等功能。项目采用Spring Boot服务层架构，代码只提供算法思路。

### 核心功能
- **路径规划**：基于网格（Grid）方式生成飞行路径。
- **相机校准**：支持3D相机校准服务，确保影像质量。

## 算法核心

该路径规划算法的核心思路是基于无人机相机的**视场角（FOV, Field of View）**、飞机传感器数据（如相机分辨率、焦距、高度等），以及指定的**横向重叠率（Side Overlap）**和**纵向重叠率（Forward Overlap）**，精确计算飞行路径的**行间距（Lateral Spacing）**和**步长（Along-Track Step）**，从而生成高效、均匀覆盖目标区域的航线路径。这确保了影像采集的完整性和重叠度，适用于摄影测量、 orthomosaic 生成或3D重建等应用。

### 算法详细描述
算法遵循摄影测量路径规划的标准原理，结合无人机实时飞行参数，实现自动化计算和路径优化。以下是核心计算流程的逐步 breakdown：

1. **输入参数采集**：
   - **FOV**：相机水平/垂直视场角（单位：度），定义了相机单次拍摄的覆盖范围。
   - **传感器数据**：包括相机分辨率（像素宽/高）、焦距（mm）、飞行高度（H, 米）。这些数据从无人机SDK（如DJI SDK）实时获取，或通过相机校准服务预配置。
   - **重叠率**：横向重叠率（典型值：60%-80%），确保相邻航线间图像重叠；纵向重叠率（典型值：70%-85%），确保同一航线内连续图像重叠。

2. **地面覆盖计算**：
   - 计算**地面采样距离（GSD, Ground Sample Distance）**：GSD = (H * 像素尺寸) / 焦距，其中像素尺寸 = 传感器宽度 / 分辨率宽度。
   - 计算**单次拍摄的地面覆盖宽度（Swath Width, SW）**：SW = 2 * H * tan(FOV / 2)。这表示相机在给定高度下覆盖的地面宽度。

3. **路径参数计算**：
   - **行间距（Lateral Spacing）**：基于横向重叠率，确保相邻航线间图像重叠。公式：行间距 = SW * (1 - 横向重叠率)。例如，若SW=50m，重叠率=70%，则行间距=15m。
   - **步长（Along-Track Step）**：基于纵向重叠率，确保前进方向图像重叠。公式：步长 = GSD * (1 - 纵向重叠率) * (图像高度 / GSD)。例如，若GSD=2cm，重叠率=80%，图像高度=4000像素，则步长 ≈ 16m（需调整为实际高度）。

4. **路径生成与优化**：
   - 使用几何服务（GeometryServiceImpl）基于多边形目标区域生成网格化航线：将区域分解为平行航段，间距为计算出的行间距。
   - 航线服务（KmlServiceImpl）处理长距离路径，优化转弯半径和避障。
   - 通过网格校准服务（GridWayCalService）应用步长，确保航点均匀分布。

5. **输出与验证**：
   - 生成Waypoint列表（经纬度、高度、速度、相机参数），生成为网格点的json。
   - 静态统计服务验证覆盖率：计算实际重叠度与预期偏差，若>5%，触发重新校准。

示例伪代码（在FlightTaskServiceImpl中实现）：
```java
public List<Waypoint> generatePath(double height, double fov, double sideOverlap, double forwardOverlap) {
    double sw = 2 * height * Math.tan(Math.toRadians(fov / 2));
    double lateralSpacing = sw * (1 - sideOverlap);
    double gsd = calculateGSD(sensorData, height);
    double step = gsd * (1 - forwardOverlap) * (imageHeight / gsd);
    
    // 生成网格航线...
    return geometryService.buildGridWaypoints(targetPolygon, lateralSpacing, step);
}
```

## 项目结构

项目采用模块化设计，主要分为实现层（impl）和接口层（Service）。以下是关键类和服务,部分服务涉及到航线规划的服务请忽略：

### 接口层（Service）
- **CameraCalService**：相机校准服务，处理影像生成前的相机参数调整（包括FOV和传感器数据校准）。
- **FlightStaticsService**：飞行静态服务，管理飞行数据的统计和监控（覆盖率验证）。
- **FlightTaskBreakpointService**：飞行任务断点服务，支持任务中断后续航。
- **WaylineJobService**：路径作业服务，管理路径任务的队列和执行。
- **WaylineRedisService**：路径Redis服务，用于路径数据的缓存和同步（存储计算参数）。

### 实现层（impl）
- **GeometryServiceImpl**：几何服务实现，支持路径的几何计算（如多边形覆盖和重叠验证）。
- **GridWayCalService**：网格路径校准服务，优化网格化覆盖（应用行间距和步长）。
- **KmServiceImpl**：公里路径服务的实现。
- **M3DCalService**：3D校准服务，针对三维影像生成（扩展FOV到立体视差）。
- **SdkWaylineService**：SDK路径服务，与无人机SDK集成生成路径。

## 许可证

MIT License. 详见 [LICENSE](LICENSE) 文件。

## 联系

- 作者： [JSS]
- Email： 419888419@qq.com

---

*最后更新：2025-12-23*
