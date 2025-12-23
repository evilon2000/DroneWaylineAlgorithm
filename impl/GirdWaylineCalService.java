package com.dji.zc.cloud.wayline.service.impl;

import com.dji.zc.cloud.manage.service.IDeviceService;
import com.dji.zc.cloud.wayline.model.dto.gird.Grid;
import com.dji.zc.cloud.wayline.model.dto.gird.GridScanLine3dDTO;
import com.dji.zc.cloud.wayline.model.dto.gird.GridScanLineDTO;
import com.dji.zc.cloud.wayline.model.dto.gird.RightTriangle;
import com.dji.zc.cloud.wayline.service.ICameraCalService;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GirdWaylineCalService {
    private final Map<String, ICameraCalService> cameraServiceMap;
    @Resource
    private IDeviceService deviceService;
    @Resource
    private GeometryCalService geometryCalService;

    @Autowired
    public GirdWaylineCalService(Map<String, ICameraCalService> cameraServiceMap) {
        this.cameraServiceMap = cameraServiceMap;
    }

    public GridScanLineDTO getGridLines(String dockSn, Point startPoint, Polygon polygon, double gsd, double overLap, double sideLap){
        var planeSn = deviceService.getChildSnBySn(dockSn);

        var plane = deviceService.getDeviceBySn(planeSn).orElseThrow();
        var cameraService = cameraServiceMap.getOrDefault(plane.getDeviceName(),new MT3DCalService());

        var flyAlt = cameraService.calFlyAlt(gsd);
        var girdDistance = cameraService.calDistance(sideLap,flyAlt);
        var girdSpace = cameraService.calSpace(overLap,flyAlt);
        var gird = new Grid(girdDistance,girdSpace,polygon,null,null);
        var linePoints = gird.getGridLines(startPoint);
        var length = gird.getUtmLineString().getLength();
        var costTimeSpan = (long)(length / 15);

        var girdLineDTO = new GridScanLineDTO();
        girdLineDTO.setCoordinates(linePoints);
        girdLineDTO.setDistance(girdDistance);
        girdLineDTO.setStep(girdSpace);
        girdLineDTO.setArea(gird.getUtmPolygon().getArea());
        girdLineDTO.setWaylineLength(length);
        girdLineDTO.setCostSecond(costTimeSpan);
        girdLineDTO.setCostTime(convertToTimeFormat(costTimeSpan));
        girdLineDTO.setPicCount((int)(length/girdSpace));
        girdLineDTO.setFlyAlt(flyAlt);

        return girdLineDTO;
    }
    public GridScanLine3dDTO getGird3dLines(String dockSn, Point startPoint, Polygon polygon, double gsd, double pitchAngle ,double overLap, double sideLap) {
        var planeSn = deviceService.getChildSnBySn(dockSn);
        var plane = deviceService.getDeviceBySn(planeSn).orElseThrow();
        var cameraService = cameraServiceMap.getOrDefault(plane.getDeviceName(),new MT3DCalService());
        var flyAlt = cameraService.calFlyAlt(gsd);


        RightTriangle triangle = new RightTriangle(flyAlt,90 + pitchAngle);
        var offset = triangle.getY();
        var gsdSurround = triangle.getZ() / triangle.getX() * gsd;
        var utmPoly = geometryCalService.createPolygon(Arrays.stream(geometryCalService.W842UTM(polygon.getCoordinates())).collect(Collectors.toList()));

        var polyLeft = geometryCalService.AffineTrans(utmPoly, 180 ,offset);
        var polyTop = geometryCalService.AffineTrans(utmPoly, 90 ,offset);
        var polyRight = geometryCalService.AffineTrans(utmPoly, 0 ,offset);
        var polyBottom = geometryCalService.AffineTrans(utmPoly, 270 ,offset);

        var w84PolyLeft = geometryCalService.createPolygon(Arrays.stream(geometryCalService.UTM2W84(polyLeft.getCoordinates())).collect(Collectors.toList()));
        var w84PolyTop = geometryCalService.createPolygon(Arrays.stream(geometryCalService.UTM2W84(polyTop.getCoordinates())).collect(Collectors.toList()));
        var w84PolyRight = geometryCalService.createPolygon(Arrays.stream(geometryCalService.UTM2W84(polyRight.getCoordinates())).collect(Collectors.toList()));
        var w84PolyBottom = geometryCalService.createPolygon(Arrays.stream(geometryCalService.UTM2W84(polyBottom.getCoordinates())).collect(Collectors.toList()));

        var girdDistance = cameraService.calDistance(sideLap,flyAlt);
        var girdSpace = cameraService.calSpace(overLap,flyAlt);

        var gridCenter = new Grid(girdDistance,girdSpace,polygon,null,null);
        var gridLeft = new Grid(girdDistance,girdSpace,w84PolyLeft,null,null);
        var gridTop = new Grid(girdDistance,girdSpace,w84PolyTop,null,null);
        var gridRight = new Grid(girdDistance,girdSpace,w84PolyRight,null,null);
        var gridBottom = new Grid(girdDistance,girdSpace,w84PolyBottom,null,null);

        var centerScanline = gridCenter.getGridLines(startPoint);
        var lengthCenter = gridCenter.getUtmLineString().getLength();
        var costTimeCenter = (long)(lengthCenter / 15);
        var gridLineCenter = new GridScanLineDTO();
        gridLineCenter.setCoordinates(centerScanline);
        gridLineCenter.setWaylineLength(lengthCenter);
        gridLineCenter.setCostSecond(costTimeCenter);
        gridLineCenter.setPicCount((int)(lengthCenter/girdSpace));

        var leftScanline = gridLeft.getGridLines(startPoint);
        var lengthLeft = gridLeft.getUtmLineString().getLength();
        var costTimeLeft = (long)(lengthLeft / 12);
        var girdLineLeft = new GridScanLineDTO();
        girdLineLeft.setCoordinates(leftScanline);
        girdLineLeft.setWaylineLength(lengthLeft);
        girdLineLeft.setCostSecond(costTimeLeft);
        girdLineLeft.setPicCount((int)(lengthLeft/girdSpace));


        var topScanline = gridTop.getVerticalGridLines(startPoint);
        var lengthTop = gridTop.getUtmLineString().getLength();
        var costTimeTop = (long)(lengthTop / 12);
        var gridLineTop = new GridScanLineDTO();
        gridLineTop.setCoordinates(topScanline);
        gridLineTop.setWaylineLength(lengthTop);
        gridLineTop.setCostSecond(costTimeTop);
        gridLineTop.setPicCount((int)(lengthTop/girdSpace));

        var rightScanline = gridRight.getGridLines(startPoint);
        var lengthRight = gridRight.getUtmLineString().getLength();
        var costTimeRight = (long)(lengthRight / 12);
        var gridLineRight = new GridScanLineDTO();
        gridLineRight.setCoordinates(rightScanline);
        gridLineRight.setWaylineLength(lengthRight);
        gridLineRight.setCostSecond(costTimeRight);
        gridLineRight.setPicCount((int)(lengthRight/girdSpace));

        var bottomScanline = gridBottom.getVerticalGridLines(startPoint);
        var lengthBottom = gridBottom.getUtmLineString().getLength();
        var costTimeBottom = (long)(lengthBottom / 12);
        var gridLineBottom = new GridScanLineDTO();
        gridLineBottom.setCoordinates(bottomScanline);
        gridLineBottom.setWaylineLength(lengthBottom);
        gridLineBottom.setCostSecond(costTimeBottom);
        gridLineBottom.setPicCount((int)(lengthBottom/girdSpace));

        Polygon[] allPoly = new Polygon[]{w84PolyLeft,w84PolyTop,w84PolyRight,w84PolyBottom};
        var hullPoly = geometryCalService.getConvexHullPolygon(allPoly);

        var totalLen = lengthCenter + lengthLeft + lengthTop + lengthRight + lengthBottom;
        var area = gridCenter.getUtmPolygon().getArea();
        var totalCost = costTimeCenter + costTimeLeft + costTimeRight + costTimeBottom + costTimeTop;
        var totalPic = gridLineCenter.getPicCount() + girdLineLeft.getPicCount() + gridLineTop.getPicCount() + gridLineRight.getPicCount() + gridLineBottom.getPicCount();

        var gird3dScanLine = new GridScanLine3dDTO();
        gird3dScanLine.setGridScanCenter(gridLineCenter);
        gird3dScanLine.setGridScanLeft(girdLineLeft);
        gird3dScanLine.setGridScanTop(gridLineTop);
        gird3dScanLine.setGridScanRight(gridLineRight);
        gird3dScanLine.setGridScanBottom(gridLineBottom);

        gird3dScanLine.setShootingPolygon(Arrays.asList(polygon.getCoordinates()));
        gird3dScanLine.setHullPolygon(Arrays.asList(hullPoly.getCoordinates()));

        gird3dScanLine.setGsd(gsd);
        gird3dScanLine.setGsdSurround(gsdSurround);
        gird3dScanLine.setTotalArea(area);
        gird3dScanLine.setTotalLength(totalLen);
        gird3dScanLine.setTotalCostTime(totalCost);
        gird3dScanLine.setTotalPicCount(totalPic);

        gird3dScanLine.setFlyAlt(flyAlt);
        gird3dScanLine.setDistance(girdDistance);
        gird3dScanLine.setStep(girdSpace);

        return gird3dScanLine;
    }

    private static String convertToTimeFormat(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if(hours == 0L){
            return String.format("%dm %ds", minutes, seconds);
        }else {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
    }

}
