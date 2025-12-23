package com.dji.zc.cloud.wayline.service;

import com.dji.zc.cloud.wayline.model.entity.FlightTaskBreakpointEntity;

public interface IFlightTaskBreakpointService {

    // 插入一条记录
    Boolean insert(FlightTaskBreakpointEntity flightTaskBreakpoint);

    // 更新一条记录
    Boolean update(FlightTaskBreakpointEntity flightTaskBreakpoint);

    // 删除记录
    int deleteByIndex(Integer index);
    FlightTaskBreakpointEntity getByJobId(String workspaceId,String jobId);
}
