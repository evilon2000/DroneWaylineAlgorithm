package com.dji.zc.cloud.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dji.zc.cloud.wayline.dao.IFlightStaticsMapper;
import com.dji.zc.cloud.wayline.model.entity.FlightStaticsEntity;
import com.dji.zc.cloud.wayline.service.IFlightStaticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class FlightStaticsServiceImpl extends ServiceImpl<IFlightStaticsMapper, FlightStaticsEntity> implements IFlightStaticsService {
    @Autowired
    private IFlightStaticsMapper flightStaticsMapper;

    @Override
    @Transactional
    public boolean saveFlightStatics(FlightStaticsEntity flightStaticsEntity) {
        return flightStaticsMapper.insert(flightStaticsEntity) > 0;
    }

    @Override
    public FlightStaticsEntity getFlightStaticsBySn(String workspaceId, String dockSn) {
        var wrapper = new LambdaQueryWrapper<FlightStaticsEntity>();
        wrapper.eq(FlightStaticsEntity::getDockSn, dockSn);
        wrapper.eq(FlightStaticsEntity::getWorkspaceId, workspaceId);
        return flightStaticsMapper.selectList(wrapper).stream().findFirst().orElse(new FlightStaticsEntity());
    }

    @Override
    @Transactional
    public boolean updateOrInsertFlightStatics(FlightStaticsEntity flightStaticsEntity) {
        FlightStaticsEntity flightStatics = flightStaticsMapper.selectOne(new LambdaQueryWrapper<FlightStaticsEntity>()
                .eq(FlightStaticsEntity::getWorkspaceId, flightStaticsEntity.getWorkspaceId())
                .eq(FlightStaticsEntity::getDockSn, flightStaticsEntity.getDockSn())
        );
        if(flightStatics != null) {
            flightStatics.setTotalFlightTime(flightStaticsEntity.getTotalFlightTime()!=null? flightStaticsEntity.getTotalFlightTime(): flightStatics.getTotalFlightTime());
            flightStatics.setTotalFlightDistance(flightStaticsEntity.getTotalFlightDistance()!=null? flightStaticsEntity.getTotalFlightDistance(): flightStatics.getTotalFlightDistance());
            flightStatics.setTotalFlightSorties(flightStaticsEntity.getTotalFlightSorties()!=null? flightStaticsEntity.getTotalFlightSorties() : flightStatics.getTotalFlightSorties());
            flightStatics.setMediaFileCount(flightStaticsEntity.getMediaFileCount()==null? flightStaticsEntity.getMediaFileCount(): flightStatics.getMediaFileCount());
            return flightStaticsMapper.updateById(flightStatics) > 0;
        }
        FlightStaticsEntity insertFlightStatics = FlightStaticsEntity.builder()
                .workspaceId(flightStaticsEntity.getWorkspaceId())
                .dockSn(flightStaticsEntity.getDockSn())
                .totalFlightSorties(flightStaticsEntity.getTotalFlightSorties())
                .totalFlightTime(flightStaticsEntity.getTotalFlightTime())
                .totalFlightDistance(flightStaticsEntity.getTotalFlightDistance())
                .mediaFileCount(flightStaticsEntity.getMediaFileCount())
                .build();
        return flightStaticsMapper.insert(insertFlightStatics) > 0;
    }
}
