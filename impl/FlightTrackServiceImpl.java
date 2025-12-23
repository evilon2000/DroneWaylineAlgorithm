package com.dji.zc.cloud.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dji.zc.cloud.wayline.dao.IFlightTrackMapper;
import com.dji.zc.cloud.wayline.model.entity.FlightTrackEntity;
import com.dji.zc.cloud.wayline.service.IFlightTrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

@Service
@Transactional
@Slf4j
public class FlightTrackServiceImpl extends ServiceImpl<IFlightTrackMapper,FlightTrackEntity> implements IFlightTrackService {
    @Resource
    private IFlightTrackMapper flightTrackMapper;

    @Override
    public Boolean saveFlightTrack(FlightTrackEntity flightTrack) {
        int insert = flightTrackMapper.insert(flightTrack);
        return insert > 0;
    }

    @Override
    public Boolean saveFlightTracks(List<FlightTrackEntity> flightTracks) {
        return this.saveBatch(flightTracks);
    }
    @Override
    public List<FlightTrackEntity> getAllFlightTrackByJobId(String jobId,String workspaceId) {
        return flightTrackMapper.selectList(new LambdaQueryWrapper<FlightTrackEntity>()
                .eq(FlightTrackEntity::getJobId, jobId)
                .eq(FlightTrackEntity::getWorkspaceId, workspaceId)
                .orderByAsc(FlightTrackEntity::getCreateTime));
    }
}
