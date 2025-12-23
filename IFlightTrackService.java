package com.dji.zc.cloud.wayline.service;

import com.dji.zc.cloud.wayline.model.entity.FlightTrackEntity;

import java.util.List;

public interface IFlightTrackService {

    Boolean saveFlightTrack (FlightTrackEntity flightTrack);
    Boolean saveFlightTracks (List<FlightTrackEntity> flightTracks);
    List<FlightTrackEntity> getAllFlightTrackByJobId(String jobId,String workspaceId);
}
