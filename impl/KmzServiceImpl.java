package com.dji.zc.cloud.wayline.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.dji.sdk.cloudapi.wayline.GetWaylineListResponse;
import com.dji.sdk.cloudapi.wayline.OutOfControlActionEnum;
import com.dji.sdk.cloudapi.wayline.TaskTypeEnum;
import com.dji.sdk.cloudapi.wayline.WaylineTypeEnum;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.zc.cloud.common.util.*;
import com.dji.zc.cloud.component.oss.model.OssConfiguration;
import com.dji.zc.cloud.component.oss.service.impl.OssServiceContext;
import com.dji.zc.cloud.manage.model.dto.DeviceDTO;
import com.dji.zc.cloud.manage.service.IDeviceService;
import com.dji.zc.cloud.wayline.model.dto.CustomMultipartFile;
import com.dji.zc.cloud.wayline.model.dto.UpdateUavRouteReq;
import com.dji.zc.cloud.wayline.model.dto.UpdateWaylineDTO;
import com.dji.zc.cloud.wayline.model.dto.gird.GridScanLineDTO;
import com.dji.zc.cloud.wayline.model.kml.*;
import com.dji.zc.cloud.wayline.model.kml.kmlEnum.ExitOnRCLostEnums;
import com.dji.zc.cloud.wayline.model.param.CreateJobParam;
import com.dji.zc.cloud.wayline.model.param.Map2DWaylineParm;
import com.dji.zc.cloud.wayline.model.param.Map3DWaylineParam;
import com.dji.zc.cloud.wayline.model.wpml.*;
import com.dji.zc.cloud.wayline.service.ICameraCalService;
import com.dji.zc.cloud.wayline.service.IFlightTaskService;
import com.dji.zc.cloud.wayline.service.IKmzService;
import com.dji.zc.cloud.wayline.service.IWaylineFileService;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import org.apache.commons.lang3.StringUtils;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@Service
public class KmzServiceImpl implements IKmzService {
    @Resource
    private OssServiceContext ossServiceContext;

    @Resource
    private IWaylineFileService waylineFileService;

    @Resource
    private IFlightTaskService flightTaskService;

    @Resource
    private GeometryCalService geometryCalService;

    private final Map<String, ICameraCalService> cameraServiceMap;

    @Autowired
    public KmzServiceImpl(Map<String, ICameraCalService> cameraServiceMap) {
        this.cameraServiceMap = cameraServiceMap;
    }

    @Override
    public void updateKmz(UpdateUavRouteReq uavRouteReq) {
        try {
            KmlParams kmlParams = new KmlParams();
            BeanUtils.copyProperties(uavRouteReq, kmlParams);
            RouteFileUtils.buildKmz(uavRouteReq.getKmzName(), kmlParams);
            String kmzTemp = RouteFileUtils.getCrossPlatformDir("kmz_temp");
            Path kmzTempPath = Paths.get(kmzTemp, uavRouteReq.getKmzName() + ".kmz").normalize();
            MultipartFile kmzFile = new CustomMultipartFile(kmzTempPath);
            UpdateWaylineDTO waylineDto = UpdateWaylineDTO.builder()
                    .waylineId(uavRouteReq.getWaylineId())
                    .kmzName(uavRouteReq.getKmzName())
                    .name(uavRouteReq.getName())
                    .workspaceId(uavRouteReq.getWorkspaceId())
                    .file(kmzFile)
                    .build();
            boolean updateStatus = waylineFileService.updateWayline(waylineDto);
            if (updateStatus) {
                deleteFile(kmzTempPath);
            }

        } catch (Exception e) {
            throw new RuntimeException("创建失败");
        }
    }

    private static void buildKmlParams(KmlParams kmlParams, KmlInfo kmlInfo) {
        KmlFolder folder = kmlInfo.getDocument().getFolder();
        kmlParams.setGlobalHeight(Double.valueOf(kmlInfo.getDocument().getKmlMissionConfig().getGlobalRTHHeight()));
        kmlParams.setAutoFlightSpeed(Double.valueOf(folder.getAutoFlightSpeed()));

        WaypointHeadingReq waypointHeadingReq = new WaypointHeadingReq();
        waypointHeadingReq.setWaypointHeadingMode(folder.getGlobalWaypointHeadingParam().getWaypointHeadingMode());
        waypointHeadingReq.setWaypointHeadingAngle(StringUtils.isNotBlank(folder.getGlobalWaypointHeadingParam().getWaypointHeadingAngle()) ?
                Double.valueOf(folder.getGlobalWaypointHeadingParam().getWaypointHeadingAngle()) : null);
        waypointHeadingReq.setWaypointPoiPoint(StringUtils.isNotBlank(folder.getGlobalWaypointHeadingParam().getWaypointPoiPoint()) ? folder.getGlobalWaypointHeadingParam().getWaypointPoiPoint() : null);
        kmlParams.setWaypointHeadingReq(waypointHeadingReq);

        WaypointTurnReq waypointTurnReq = new WaypointTurnReq();
        waypointTurnReq.setWaypointTurnMode(folder.getGlobalWaypointTurnMode());
        waypointTurnReq.setUseStraightLine(StringUtils.isNotBlank(folder.getGlobalUseStraightLine()) ? Integer.valueOf(folder.getGlobalUseStraightLine()) : null);

        kmlParams.setWaypointTurnReq(waypointTurnReq);
        kmlParams.setGimbalPitchMode(folder.getGimbalPitchMode());
        kmlParams.setPayloadPosition(Integer.valueOf(kmlInfo.getDocument().getKmlMissionConfig().getPayloadInfo().getPayloadPositionIndex()));
        kmlParams.setImageFormat(folder.getPayloadParam().getImageFormat());
    }

    private void handleRouteUpdate(KmlInfo kmlInfo, UavRouteReq uavRouteReq, String fileType, KmlParams kmlParams) {
        if (StringUtils.isNotBlank(uavRouteReq.getFinishAction())) {
            kmlInfo.getDocument().getKmlMissionConfig().setFinishAction(uavRouteReq.getFinishAction());
        }
        if (StringUtils.isNotBlank(uavRouteReq.getExitOnRcLostAction())) {
            kmlInfo.getDocument().getKmlMissionConfig().setExitOnRCLost(ExitOnRCLostEnums.EXECUTE_LOST_ACTION.getValue());
            kmlInfo.getDocument().getKmlMissionConfig().setExecuteRCLostAction(uavRouteReq.getExitOnRcLostAction());
        }
        if (CollectionUtil.isNotEmpty(uavRouteReq.getRoutePointList())) {
            List<KmlPlacemark> placemarkList = new ArrayList<>();
            for (RoutePointReq routePointReq : uavRouteReq.getRoutePointList()) {
                KmlPlacemark kmlPlacemark = RouteFileUtils.buildKmlPlacemark(routePointReq, kmlParams, fileType);
                placemarkList.add(kmlPlacemark);
            }
            kmlInfo.getDocument().getFolder().setPlacemarkList(placemarkList);
        }
    }

    @Override
    public void buildKmz(UavRouteReq uavRouteReq) {
        try {
            String creator =  UserDataUtils.getUserData().getUserName();
            KmlParams kmlParams = new KmlParams();
            BeanUtils.copyProperties(uavRouteReq, kmlParams);
            String kmzTemp = RouteFileUtils.getCrossPlatformDir("kmz_temp");
            RouteFileUtils.buildKmz(uavRouteReq.getKmzName(), kmlParams);
            String zipKey = OssConfiguration.objectDirPrefix + "/" +uavRouteReq.getWorkspaceId()+"/"+ uavRouteReq.getKmzName() + ".kmz";
            Path kmzTempPath = Paths.get(kmzTemp, uavRouteReq.getKmzName() + ".kmz").normalize();
            MultipartFile kmzFile = new CustomMultipartFile(kmzTempPath);
            String waylineFile = waylineFileService.importKmzInputStreamToDock(kmzFile, uavRouteReq.getDockSn(), uavRouteReq.getWorkspaceId(), creator, uavRouteReq.getKmzName() + ".kmz", uavRouteReq.getIsSave());
            if (StringUtils.isNotBlank(waylineFile)) {
                deleteFile(kmzTempPath);
                if (uavRouteReq.getIsSave() == null || uavRouteReq.getIsSave() == 0) {
                    CreateJobParam param = new CreateJobParam();
                    param.setRthAltitude(uavRouteReq.getRthAltitude());
                    param.setName(uavRouteReq.getKmzName());
                    param.setFileId(waylineFile);
                    param.setDockSn(uavRouteReq.getDockSn());
                    param.setWaylineType(WaylineTypeEnum.find(uavRouteReq.getTemplateType()));
                    param.setTaskType(TaskTypeEnum.IMMEDIATE);
                    param.setOutOfControlAction(OutOfControlActionEnum.RETURN_TO_HOME);
                    flightTaskService.publishFlightTask(param,
                            uavRouteReq.getWorkspaceId() );
                }
            } else {
//                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static boolean isUUID(String waylineId) {
        if (waylineId == null) return false;
        // 检查长度和基本格式
        if (waylineId.length() != 36) return false;
        // 使用正则表达式验证
        String uuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
        return waylineId.matches(uuidPattern);
    }

    @Override
    public HttpResultResponse parseKml(String waylineId,String workspaceId) {
        if (!isUUID(waylineId)) {
            return HttpResultResponse.error("航线id错误");
        }

        try {
            Optional<GetWaylineListResponse> waylineByWaylineId = waylineFileService.getWaylineByWaylineId(workspaceId, waylineId);
            if (!waylineByWaylineId.isPresent()) {
                return HttpResultResponse.error("查找不到该航线");
            }
            InputStream waylineFileInputStream = ossServiceContext.getObject(OssConfiguration.bucket,waylineByWaylineId.get().getObjectKey());
            InputStream kmlInputstream = RouteFileUtils.extractSingleKmlStreamFromKmz(waylineFileInputStream, ".kml");
            KmlInfo kmlInfo = RouteFileUtils.parseKml(kmlInputstream);
            return HttpResultResponse.success(kmlInfo);
        } catch (IOException e) {
            return HttpResultResponse.error("解析航线失败");
        }
    }

    @Override
    public Mapping2dParseDTO parseWpml(String waylineId,String workspaceId) {
        if (!isUUID(waylineId)) {
            throw new RuntimeException("航线id错误");
        }
        try {
            Optional<GetWaylineListResponse> waylineByWaylineId = waylineFileService.getWaylineByWaylineId(workspaceId, waylineId);
            if (!waylineByWaylineId.isPresent()) {
                throw new RuntimeException("查找不到该航线");
            }
            var dockSn = waylineByWaylineId.get().getDockSn();
            var planeSn = deviceService.getChildSnBySn(dockSn);
            var plane = deviceService.getDeviceBySn(planeSn).orElseThrow();
            var cameraService = cameraServiceMap.getOrDefault(plane.getDeviceName(),new MT3DCalService());


            InputStream waylineFileInputStream = waylineFileService.getObjectFile(workspaceId, waylineId);
            byte[] waylineBytes = RouteFileUtils.toByteArray(waylineFileInputStream);
            InputStream wpmlInputstream = RouteFileUtils.extractSingleKmlStreamFromKmz(new ByteArrayInputStream(waylineBytes), ".wpml");
            InputStream regionInputstream = RouteFileUtils.extractSingleKmlStreamFromKmz(new ByteArrayInputStream(waylineBytes), ".kml");
            WpmlInfo kmlInfo = RouteFileUtils.parseWpml(wpmlInputstream);
            RegionInfo regionInfo = RouteFileUtils.parseRegion(regionInputstream);

            Mapping2dParseDTO build = Mapping2dParseDTO.builder().wpmlInfo(kmlInfo).regionInfo(regionInfo).build();
            var poy = Arrays.stream(regionInfo.getDocument().getFolder().getPlacemark().getPolygon().getOuterBoundaryIs().getLinearRing().getCoordinates()
                    .trim().replaceAll("\\s+"," ").split(" "))
                    .map(cood->{
                        var coodArr = cood.split(",");
                        Coordinate coordinate = new Coordinate( Double.parseDouble(coodArr[0]),Double.parseDouble(coodArr[1]));
                        return coordinate;
                    }).toArray(Coordinate[]::new);
            var scanline = kmlInfo.getDocument().getFolder().getPlacemarkList().stream().map(wpmlPlacemark -> {
                var point = wpmlPlacemark.getKmlPoint().getCoordinates().trim().replaceAll("\\s+","").split(",");
                var height = wpmlPlacemark.getExecuteHeight();
                Coordinate coordinate = new Coordinate( Double.parseDouble(point[0]),Double.parseDouble(point[1]),height);
                return coordinate;
            }).toArray(Coordinate[]::new);

            var flyAlt = regionInfo.getDocument().getFolder().getWaylineCoordinateSysParam().getGlobalShootHeight();
            var flyAsl = scanline[0].z;
            var coordUTM = geometryCalService.W842UTM(scanline);
            var lineUTM = geometryCalService.createLine(coordUTM);
            var poyCoordUTM = geometryCalService.W842UTM(poy);
            var poyUTM = geometryCalService.createPolygon(Arrays.stream(poyCoordUTM).collect(Collectors.toList()));

            var area = poyUTM.getArea();
            var asl = flyAsl - flyAlt;
            var len = lineUTM.getLength();
            var overLap = Double.parseDouble(regionInfo.getDocument().getFolder().getPlacemark().getOverlap().getOrthoCameraOverlapH()) / 100;
            var sideLap = Double.parseDouble(regionInfo.getDocument().getFolder().getPlacemark().getOverlap().getOrthoCameraOverlapW()) / 100;
            var distance = cameraService.calDistance(sideLap, flyAlt);
            var space = cameraService.calSpace(overLap,flyAlt);
            var costTimeSpan = (long)(len / 15);
            var picCount = (int)(len/space);
            var gsd = cameraService.calGSD(flyAlt);


            build.setWaylineType(waylineByWaylineId.get().getWaylineType());
            build.setAsl(asl);
            build.setArea(area);
            build.setWaylineLength(len);
            build.setAngle(90);
            build.setCostSecond(costTimeSpan);
            build.setFlySpeed(15);
            build.setOverLap(overLap);
            build.setSideLap(sideLap);
            build.setPicCount(picCount);
            build.setPolygon(poy);
            build.setScanline(scanline);
            build.setGsd(gsd);
            build.setFlyAsl(flyAsl);
            build.setFlyAlt(flyAlt);

            return build;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Mapping3dParseDTO parse3DWpml(String waylineId, String workspaceId) {

        Optional<GetWaylineListResponse> waylineByWaylineId = waylineFileService.getWaylineByWaylineId(workspaceId, waylineId);
        if (!waylineByWaylineId.isPresent()) {
            throw new RuntimeException("查找不到该航线");
        }
        var dockSn = waylineByWaylineId.get().getDockSn();
        var planeSn = deviceService.getChildSnBySn(dockSn);
        var plane = deviceService.getDeviceBySn(planeSn).orElseThrow();
        var cameraService = cameraServiceMap.getOrDefault(plane.getDeviceName(),new MT3DCalService());

        try {
            InputStream waylineFileInputStream = waylineFileService.getObjectFile(workspaceId, waylineId);
            byte[] waylineBytes = RouteFileUtils.toByteArray(waylineFileInputStream);
            InputStream wpmlInputstream = RouteFileUtils.extractSingleKmlStreamFromKmz(new ByteArrayInputStream(waylineBytes), ".wpml");
            InputStream regionInputstream = RouteFileUtils.extractSingleKmlStreamFromKmz(new ByteArrayInputStream(waylineBytes), ".kml");
            RegionInfo regionInfo = RouteFileUtils.parseRegion(regionInputstream);
            WpmlInfo3D wpmlInfo3D = RouteFileUtils.parse3DWpml(wpmlInputstream);

            Mapping3dParseDTO mapping3dParseDTO = new Mapping3dParseDTO();
            var poy = Arrays.stream(regionInfo.getDocument().getFolder().getPlacemark().getPolygon().getOuterBoundaryIs().getLinearRing().getCoordinates()
                            .trim().replaceAll("\\s+"," ").split(" "))
                    .map(cood->{
                        var coodArr = cood.split(",");
                        Coordinate coordinate = new Coordinate( Double.parseDouble(coodArr[0]),Double.parseDouble(coodArr[1]));
                        return coordinate;
                    }).toArray(Coordinate[]::new);

            var scanLines =  wpmlInfo3D.getDocument().getFolder().stream().map(x->{
                return x.getPlacemarkList().stream().map(wpmlPlacemark -> {
                    var point = wpmlPlacemark.getKmlPoint().getCoordinates().trim().replaceAll("\\s+","").split(",");
                    var height = wpmlPlacemark.getExecuteHeight();
                    Coordinate coordinate = new Coordinate( Double.parseDouble(point[0]),Double.parseDouble(point[1]),height);
                    return coordinate;
                }).toArray(Coordinate[]::new);
            }).collect(Collectors.toList());

            var poyCoordUTM = geometryCalService.W842UTM(poy);
            var poyUTM = geometryCalService.createPolygon(Arrays.stream(poyCoordUTM).collect(Collectors.toList()));

            var overLap = Double.parseDouble(regionInfo.getDocument().getFolder().getPlacemark().getOverlap().getOrthoCameraOverlapH()) / 100;
            var sideLap = Double.parseDouble(regionInfo.getDocument().getFolder().getPlacemark().getOverlap().getOrthoCameraOverlapW()) / 100;

            var flyAlt = regionInfo.getDocument().getFolder().getWaylineCoordinateSysParam().getGlobalShootHeight();
            var flyAsl = scanLines.get(0)[0].z;
            var space = cameraService.calSpace(overLap,flyAlt);
            var angleSurround = regionInfo.getDocument().getFolder().getPlacemark().getInclinedGimbalPitch();


            List<GridScanLineDTO> gridScanLineDTOS = new ArrayList<>();
            scanLines.forEach(scanline->{
                var coordUTM = geometryCalService.W842UTM(scanline);
                var lineUTM = geometryCalService.createLine(coordUTM);
                var len = lineUTM.getLength();
                var asl = flyAsl - flyAlt;
                GridScanLineDTO tempScanline = new GridScanLineDTO();
                tempScanline.setCoordinates(scanline);
                tempScanline.setUtmCoordinates(coordUTM);
                tempScanline.setWaylineLength(len);
                var costTimeSpan = (long)(len / 12);
                var picCount = (int)(len/space);
                tempScanline.setCostSecond(costTimeSpan);
                tempScanline.setPicCount(picCount);
                gridScanLineDTOS.add(tempScanline);
//
//                var distance = cameraService.calDistance(sideLap, flyAlt);
//                var space = cameraService.calSpace(overLap,flyAlt);
//                var costTimeSpan = (long)(len / 15);
//                var picCount = (int)(len/space);
//                var gsd = cameraService.calGSD(flyAlt);
            });
            var totalArea = poyUTM.getArea();
            var totalLength = gridScanLineDTOS.stream().mapToDouble(GridScanLineDTO::getWaylineLength).sum();
            var totalCostTime = gridScanLineDTOS.stream().mapToLong(GridScanLineDTO::getCostSecond).sum();
            var totalPicCount = gridScanLineDTOS.stream().mapToDouble(GridScanLineDTO::getPicCount).sum();
            var gsd = cameraService.calGSD(flyAlt);
            var centerPoint = gridScanLineDTOS.get(0).getUtmCoordinates()[0];
            var leftPoint = gridScanLineDTOS.get(1).getUtmCoordinates()[0];
            var rightLine = GeometryCalService.calculateDistance(centerPoint, leftPoint);
            var hypLine = Math.sqrt(rightLine * rightLine + flyAlt * flyAlt);
            var gsdSurround = hypLine / flyAlt * gsd;
            var hullPoy = geometryCalService.getConvexHullPolygon(scanLines.stream().flatMap(Arrays::stream).collect(Collectors.toList()));

//            mapping3dParseDTO.setScanline(scanLines);
            mapping3dParseDTO.setPolygon(poy);
            mapping3dParseDTO.setWaylineType(waylineByWaylineId.get().getWaylineType());
            mapping3dParseDTO.setAsl(flyAsl - flyAlt);
            mapping3dParseDTO.setAngleSurround(Double.parseDouble(angleSurround));
            mapping3dParseDTO.setAsl(flyAsl - flyAlt);
            mapping3dParseDTO.setFlyAlt(flyAlt);
            mapping3dParseDTO.setFlyAsl(flyAsl);
            mapping3dParseDTO.setTotalArea(totalArea);
            mapping3dParseDTO.setTotalLength(totalLength);
            mapping3dParseDTO.setTotalCostTime(totalCostTime);
            mapping3dParseDTO.setTotalPicCount((int) Math.round(totalPicCount));
            mapping3dParseDTO.setOverLap(overLap);
            mapping3dParseDTO.setSideLap(sideLap);
            mapping3dParseDTO.setGsdCenter(gsd);
            mapping3dParseDTO.setGsdSurround(gsdSurround);
            mapping3dParseDTO.setHull_polygon(hullPoy.getCoordinates());
            mapping3dParseDTO.setGridScanCenter(gridScanLineDTOS.get(0));
            mapping3dParseDTO.setGridScanLeft(gridScanLineDTOS.get(1));
            mapping3dParseDTO.setGridScanTop(gridScanLineDTOS.get(2));
            mapping3dParseDTO.setGridScanRight(gridScanLineDTOS.get(3));
            mapping3dParseDTO.setGridScanBottom(gridScanLineDTOS.get(4));



            return mapping3dParseDTO;
        }catch (Exception e){
            throw new RuntimeException(e);
        }

    }


    public static boolean deleteFile(Path path) {
        try {
            // 检查文件是否存在
            if (!Files.exists(path)) {
                return false; // 文件不存在，返回 false
            }

            // 检查是否为文件（而不是目录）
            if (!Files.isRegularFile(path)) {
                return false; // 不是文件，返回 false
            }

            // 删除文件
            Files.delete(path);
            return true;

        } catch (Exception e) {
            // 捕获其他未预期异常
            return false;
        }
    }

    @Resource
    private IDeviceService deviceService;

    @Override
    public void buildMap2DKmz(Map2DWaylineParm param) {
        try {
            String creator = UserDataUtils.getUserData().getUserName();
            String childSnBySn = deviceService.getChildSnBySn(param.getDockSn());
            DeviceDTO deviceDetail = deviceService.getDeviceDetailBySn(childSnBySn);
            Map2DKmlInfo map2DKmlInfo = Map2DKmlUtils.buildKml(deviceDetail.getDeviceName(), param.getAsl() + param.getFlyAlt(), param.getFlyAlt(), param.getOverLap(), param.getSideLap(), param.getPolygon());
            Map2DWpmlInfo map2DWpmlInfo = Map2DWpmlUtils.xml2Wpml(deviceDetail.getDeviceName(), param.getWaylineLength(), param.getScanLines().toArray(new Coordinate[0]), param.getAsl() + param.getFlyAlt(),param.getStep());
            buildKmz(param.getFileName(), map2DKmlInfo, map2DWpmlInfo);
            String kmzTemp = RouteFileUtils.getCrossPlatformDir("kmz_temp");
            Path kmzTempPath = Paths.get(kmzTemp, param.getFileName() + ".kmz").normalize();
            MultipartFile kmzFile = new CustomMultipartFile(kmzTempPath);
            String waylineFile = waylineFileService.importMap2dKmzFile(kmzFile, param,  creator,deviceDetail);
            if (StringUtils.isNotBlank(waylineFile)) {
                deleteFile(kmzTempPath);
            } else {

            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public void buildMap3DKmz(Map3DWaylineParam param) {
        try {
            String creator = UserDataUtils.getUserData().getUserName();
            String childSnBySn = deviceService.getChildSnBySn(param.getDockSn());
            DeviceDTO deviceDetail = deviceService.getDeviceDetailBySn(childSnBySn);
            Map3DKmlInfo map3DKmlInfo = Map3DKmlUtils.buildKml(deviceDetail.getDeviceName(),
                    param.getAsl() + param.getFlyAlt(),
                    param.getFlyAlt(),
                    param.getOverLap(),
                    param.getSideLap(),
                    param.getPitchAngle(),
                    param.getPolygon());

            Double[] distances = new Double[5];
            distances[0] = param.getGridScanCenter().getWaylineLength();
            distances[1] = param.getGridScanLeft().getWaylineLength();
            distances[2] = param.getGridScanTop().getWaylineLength();
            distances[3] = param.getGridScanRight().getWaylineLength();
            distances[4] = param.getGridScanBottom().getWaylineLength();

            Map3DWpmlInfo map3DWpmlInfo = Map3DWpmlUtils.xml2Wpml(childSnBySn,distances,param.getGridScanCenter().getCoordinates(),
                    param.getGridScanLeft().getCoordinates(),
                    param.getGridScanTop().getCoordinates()
                    ,param.getGridScanRight().getCoordinates(),
                    param.getGridScanBottom().getCoordinates(),
                    param.getPitchAngle(),
                    param.getAsl() + param.getFlyAlt(),
                    param.getStep());
            build3DKmz(param.getFileName(), map3DKmlInfo, map3DWpmlInfo);
            String kmzTemp = RouteFileUtils.getCrossPlatformDir("kmz_temp");
            Path kmzTempPath = Paths.get(kmzTemp, param.getFileName() + ".kmz").normalize();
            MultipartFile kmzFile = new CustomMultipartFile(kmzTempPath);
            String waylineFile = waylineFileService.importMap3dKmzFile(kmzFile, param,  creator,deviceDetail);
            if (StringUtils.isNotBlank(waylineFile)) {
                deleteFile(kmzTempPath);
            } else {

            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private void build3DKmz(String fileName, Map3DKmlInfo kmlInfo, Map3DWpmlInfo wpmlInfo) {
        XStream xStream = new XStream(new DomDriver());
        xStream.processAnnotations(Map3DKmlInfo.class);
        xStream.processAnnotations(Map3DWpmlInfo.class);

        String kml = XML_HEADER + xStream.toXML(kmlInfo);
        String wpml = XML_HEADER + xStream.toXML(wpmlInfo);
        String dirPath = getCrossPlatformDir("kmz_temp");
        try (FileOutputStream fileOutputStream = new FileOutputStream(dirPath + "/" + fileName + ".kmz");
             ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream)) {
            zipOutputStream.setLevel(0); // 0 表示不压缩，存储方式

            // 创建 wpmz 目录中的 template.kml 文件条目
            buildZipFile("wpmz/template.kml", zipOutputStream, kml);

            // 创建 wpmz 目录中的 waylines.wpml 文件条目
            buildZipFile("wpmz/waylines.wpml", zipOutputStream, wpml);

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }


    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    public static void buildKmz(String fileName, Map2DKmlInfo kmlInfo, Map2DWpmlInfo wpmlInfo) {
        XStream xStream = new XStream(new DomDriver());

        xStream.processAnnotations(Map2DKmlInfo.class);
        xStream.processAnnotations(Map2DWpmlInfo.class);

        String kml = XML_HEADER + xStream.toXML(kmlInfo);
        String wpml = XML_HEADER + xStream.toXML(wpmlInfo);
        String dirPath = getCrossPlatformDir("kmz_temp");
        try (FileOutputStream fileOutputStream = new FileOutputStream(dirPath + "/" + fileName + ".kmz");
             ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream)) {
            zipOutputStream.setLevel(0); // 0 表示不压缩，存储方式

            // 创建 wpmz 目录中的 template.kml 文件条目
            buildZipFile("wpmz/template.kml", zipOutputStream, kml);

            // 创建 wpmz 目录中的 waylines.wpml 文件条目
            buildZipFile("wpmz/waylines.wpml", zipOutputStream, wpml);

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    // 获取跨平台目录的方法
    public static String getCrossPlatformDir(String subDirName) {
        String userHome = System.getProperty("user.home");
        String dirPath = userHome + File.separator + subDirName;
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dirPath;
    }

    private static void buildZipFile(String name, ZipOutputStream zipOutputStream, String content) throws IOException {
        ZipEntry kmlEntry = new ZipEntry(name);
        zipOutputStream.putNextEntry(kmlEntry);
        // 将内容写入 ZIP 条目
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) >= 0) {
                zipOutputStream.write(buffer, 0, length);
            }
        }
        zipOutputStream.closeEntry(); // 关闭条目
    }
}
