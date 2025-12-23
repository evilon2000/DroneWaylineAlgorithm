package com.dji.zc.cloud.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dji.zc.cloud.wayline.dao.IFlightTaskBreakpointMapper;
import com.dji.zc.cloud.wayline.model.entity.FlightTaskBreakpointEntity;
import com.dji.zc.cloud.wayline.service.IFlightTaskBreakpointService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class FlightTaskBreakpointServiceImpl implements IFlightTaskBreakpointService {

    @Autowired
    private IFlightTaskBreakpointMapper flightTaskBreakpointMapper;
    @Override
    public Boolean insert(FlightTaskBreakpointEntity flightTaskBreakpoint) {
        int insert = flightTaskBreakpointMapper.insert(flightTaskBreakpoint);
        return insert>0;
    }

    @Override
    public Boolean update(FlightTaskBreakpointEntity flightTaskBreakpoint) {
        return false;
    }

    @Override
    public int deleteByIndex(Integer index) {
        return 0;
    }

    @Override
    public FlightTaskBreakpointEntity getByJobId(String workspaceId,String jobId) {
        return flightTaskBreakpointMapper.selectList(
                new LambdaQueryWrapper<FlightTaskBreakpointEntity>()
                        .eq(FlightTaskBreakpointEntity::getWorkspaceId, workspaceId)
                        .eq(StringUtils.isNotBlank(jobId),FlightTaskBreakpointEntity::getJobId, jobId)
        ).stream().findFirst().orElse(null);
    }
}
