package com.dji.zc.cloud.wayline.service;

import com.dji.zc.cloud.wayline.model.entity.FlightStaticsEntity;

public interface IFlightStaticsService {

    // 保存飞行统计数据
    boolean saveFlightStatics(FlightStaticsEntity flightStaticsEntity);

    // 根据飞行任务ID查询飞行统计数据
    FlightStaticsEntity getFlightStaticsBySn(String workspaceId,String dockSn);

    // 更新飞行统计数据
    boolean updateOrInsertFlightStatics(FlightStaticsEntity flightStaticsEntity);
}
