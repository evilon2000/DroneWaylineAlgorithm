package com.dji.zc.cloud.wayline.service;

public interface ICameraCalService {
    double calDistance(double sideLap,double flyAlt);
    double calSpace(double overLap, double flyAlt);
    double calFlyAlt(double gsd);
    double calGSD(double flyAlt);
}
