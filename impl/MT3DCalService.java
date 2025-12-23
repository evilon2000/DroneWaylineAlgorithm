package com.dji.zc.cloud.wayline.service.impl;

import com.dji.zc.cloud.wayline.model.dto.gird.CameraInfo;
import com.dji.zc.cloud.wayline.model.dto.gird.FOV;
import com.dji.zc.cloud.wayline.service.ICameraCalService;
import org.springframework.stereotype.Service;

@Service("MT3D")
public class MT3DCalService implements ICameraCalService {
    private final CameraInfo cameraInfo = new CameraInfo("MT3D",0.2356,9.50,7.125,6.7);

    @Override
    public double calDistance(double sideLap,double flyAlt) {
        var fov = new FOV(cameraInfo,flyAlt);
        return fov.getFOVWidth() * (1 - sideLap);
    }

    @Override
    public double calSpace(double overLap, double flyAlt) {
        var fov = new FOV(cameraInfo,flyAlt);
        return fov.getFOVHeight() * (1 - overLap);
    }

    @Override
    public double calFlyAlt(double gsd) {
        return gsd * cameraInfo.getSensorFocLen() / cameraInfo.getPixelCamera();
    }

    @Override
    public double calGSD(double flyAlt) {
        return flyAlt * cameraInfo.getPixelCamera() / cameraInfo.getSensorFocLen();
    }
}
