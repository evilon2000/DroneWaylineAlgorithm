package com.dji.zc.cloud.wayline.service.impl;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.osgeo.proj4j.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class GeometryCalService {
    public Polygon createPolygon(List<Coordinate> coordinates ) {
        if(coordinates.size() < 3){
            throw new IllegalArgumentException("创建面至少需要三个点");
        }
        if(!coordinates.get(0).equals(coordinates.get(coordinates.size()-1))){
            coordinates.add(coordinates.get(0));
        }
        var factory = new GeometryFactory();
        var linearRing = factory.createLinearRing(coordinates.toArray(Coordinate[]::new));
        return factory.createPolygon(linearRing,null );
    }
    public Point createPoint(Coordinate coordinate) {
        var factory = new GeometryFactory();
        return factory.createPoint(coordinate);
    }

    public LineString createLine(Coordinate[] linePoints) {
        var factory = new GeometryFactory();
        return factory.createLineString(linePoints);
    }

    public Coordinate[] W842UTM(Coordinate[] w84Coordinates){
        CoordinateReferenceSystem wgs84 = new CRSFactory().createFromName("EPSG:4326");
        CoordinateReferenceSystem utm = new CRSFactory().createFromName("EPSG:32649");
        CoordinateTransform tranToUTM = new CoordinateTransformFactory().createTransform(wgs84, utm);

        Coordinate[]  utmCoordinate = new Coordinate[w84Coordinates.length];
        for(int i = 0; i < w84Coordinates.length; i++){
            ProjCoordinate w84coord = new ProjCoordinate(w84Coordinates[i].x, w84Coordinates[i].y);
            ProjCoordinate utmCoord = new ProjCoordinate();
            tranToUTM.transform(w84coord, utmCoord);
            utmCoordinate[i] = new Coordinate(utmCoord.x, utmCoord.y);
        }
        return utmCoordinate;
    }

    public Coordinate[] UTM2W84(Coordinate[] utmCoordinate){
        CoordinateReferenceSystem wgs84 = new CRSFactory().createFromName("EPSG:4326");
        CoordinateReferenceSystem utm = new CRSFactory().createFromName("EPSG:32649");

        CoordinateTransform tranToW84 = new CoordinateTransformFactory().createTransform(utm, wgs84);

        Coordinate[]  wgs84Coordinate = new Coordinate[utmCoordinate.length];
        for(int i = 0; i < utmCoordinate.length; i++){
            ProjCoordinate w84coord = new ProjCoordinate(utmCoordinate[i].x, utmCoordinate[i].y);
            ProjCoordinate utmCoord = new ProjCoordinate();
            tranToW84.transform(w84coord, utmCoord);
            wgs84Coordinate[i] = new Coordinate(utmCoord.x, utmCoord.y);
        }
        return wgs84Coordinate;
    }

    /**
     * 将地面距离转换为纬度方向的角度（度）
     * @param distance 地面距离（米）
     * @return 纬度方向角度（度）
     */
    public static double convertDistanceToLatitudeDegrees(double distance) {
        return distance / 111_000.0;
    }

    /**
     * 将地面距离转换为经度方向的角度（度）
     * @param distance 地面距离（米）
     * @param latitude 参考纬度（度）
     * @return 经度方向角度（度）
     */
    public static double convertDistanceToLongitudeDegrees(double distance, double latitude) {
        return distance / (111_000.0 * Math.cos(Math.toRadians(latitude)));
    }

    /**
     * Translates a polygon by a given distance along a specified angle.
     *
     * @param originalPolygon The input polygon to be translated.
     * @param angleDeg The angle of translation in degrees.
     * @param distance The distance to translate the polygon.
     * @return The translated polygon.
     * @throws IllegalArgumentException if the input polygon is null or invalid.
     */
    public Polygon AffineTrans(Polygon originalPolygon,double angleDeg,double distance) {
        // 输入验证
        if (originalPolygon == null) {
            throw new IllegalArgumentException("输入的面不可为空");
        }
        if (!originalPolygon.isValid()) {
            throw new IllegalArgumentException("输入的面验证失败");
        }
        if (Double.isNaN(angleDeg) || Double.isNaN(distance)) {
            throw new IllegalArgumentException("平移角度和距离不可为空");
        }

        // 将角度转换为弧度
        double angleRad = Math.toRadians(angleDeg);

        // 计算平移向量
        double dx = distance * Math.cos(angleRad);
        double dy = distance * Math.sin(angleRad);

        // 创建平移变换
        AffineTransformation translation = AffineTransformation.translationInstance(dx, dy);

        // 应用平移变换到多边形
        Geometry translatedGeometry = translation.transform(originalPolygon);

        if (!(translatedGeometry instanceof Polygon)) {
            throw new IllegalStateException("Transformed geometry is not a valid Polygon");
        }
        return (Polygon) translatedGeometry;
    }

    /**
     * 计算包含多个多边形的凸包
     * @param polygons 四个偏移的多边形
     * @return 包含所有多边形的凸包多边形
     */
    public Polygon getConvexHullPolygon(Polygon[] polygons) {
        if (polygons == null || polygons.length == 0) {
            throw new IllegalArgumentException("多边形数组不能为空");
        }

        GeometryFactory factory = new GeometryFactory();
        List<Coordinate> allCoordinates = new ArrayList<>();

        // 收集所有多边形的坐标
        for (Polygon polygon : polygons) {
            if (polygon != null && !polygon.isEmpty()) {
                allCoordinates.addAll(Arrays.asList(polygon.getCoordinates()));
            }
        }

        if (allCoordinates.isEmpty()) {
            throw new IllegalStateException("没有有效的多边形坐标");
        }

        // 创建MultiPoint并计算凸包
        Coordinate[] coords = allCoordinates.toArray(new Coordinate[0]);
        MultiPoint multiPoint = factory.createMultiPointFromCoords(coords);

        Geometry convexHull = multiPoint.convexHull();

        // 确保结果是Polygon
        if (convexHull instanceof Polygon) {
            return (Polygon) convexHull;
        } else {
            throw new IllegalStateException("凸包计算结果不是多边形");
        }
    }

    public Polygon getConvexHullPolygon(List<Coordinate> allCoordinates) {
        GeometryFactory factory = new GeometryFactory();
        // 创建MultiPoint并计算凸包
        Coordinate[] coords = allCoordinates.toArray(new Coordinate[0]);
        MultiPoint multiPoint = factory.createMultiPointFromCoords(coords);

        Geometry convexHull = multiPoint.convexHull();

        // 确保结果是Polygon
        if (convexHull instanceof Polygon) {
            return (Polygon) convexHull;
        } else {
            throw new IllegalStateException("凸包计算结果不是多边形");
        }
    }

    /**
     * 计算两个 Coordinate 对象之间的欧几里得距离（单位：米）
     * @param point1 第一个点的坐标
     * @param point2 第二个点的坐标
     * @return 两点间的距离（米）
     */
    public static double calculateDistance(Coordinate point1, Coordinate point2) {
        double deltaX = point2.getX() - point1.getX();
        double deltaY = point2.getY() - point1.getY();
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    }
}
