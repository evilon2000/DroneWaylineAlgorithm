package com.dji.zc.cloud.wayline.service;

import com.dji.sdk.cloudapi.control.Point;
import com.dji.sdk.cloudapi.wayline.FlighttaskProgress;
import com.dji.zc.cloud.component.mqtt.model.EventsReceiver;
import com.dji.zc.cloud.wayline.model.dto.ConditionalWaylineJobKey;
import com.dji.zc.cloud.wayline.model.dto.WaylineJobDTO;

import javax.validation.constraints.NotBlank;
import java.util.Optional;

/**
 * @author sean
 * @version 1.4
 * @date 2023/3/24
 */
public interface IWaylineRedisService {

    /**
     * Save the status of the wayline job performed by the dock into redis.
     * @param dockSn
     * @param data
     */
    void setRunningWaylineJob(String dockSn, EventsReceiver<FlighttaskProgress> data);
    void setRunningJobDTO(String dockSn, WaylineJobDTO runningJobDTO);
    Optional<WaylineJobDTO>  getRunningJob(String dockSn);
    /**
     * Query the status of wayline job performed by the dock in redis.
     * @param dockSn
     * @return
     */
    Optional<EventsReceiver<FlighttaskProgress>> getRunningWaylineJob(String dockSn);

    /**
     * Delete the wayline job status of the dock operation in redis.
     * @param dockSn
     * @return
     */
    Boolean delRunningWaylineJob(String dockSn);
    void delRunningJobDTO(String gateway);
    /**
     * Save the wayline job suspended by the dock to redis.
     * @param dockSn
     * @param jobId
     */
    void setPausedWaylineJob(String dockSn, String jobId);

    /**
     * Query the wayline job id suspended by the dock in redis.
     * @param dockSn
     * @return
     */
    String getPausedWaylineJobId(String dockSn);

    /**
     * Delete the wayline job suspended by the dock in redis.
     * @param dockSn
     * @return
     */
    Boolean delPausedWaylineJob(String dockSn);

    /**
     * Save the wayline job blocked by the dock to redis.
     * @param dockSn
     * @param jobId
     */
    void setBlockedWaylineJob(String dockSn, String jobId);

    /**
     * Query the wayline job id blocked by the dock in redis.
     * @param dockSn
     * @return
     */
    String getBlockedWaylineJobId(String dockSn);

    /**
     * Save the conditional wayline job by the dock to redis.
     * @param waylineJob
     */
    void setConditionalWaylineJob(WaylineJobDTO waylineJob);

    /**
     * Query the conditional wayline job id by the dock in redis.
     * @param jobId
     * @return
     */
    Optional<WaylineJobDTO> getConditionalWaylineJob(String jobId);

    /**
     * Delete the conditional wayline job by the dock in redis.
     * @param jobId
     * @return
     */
    Boolean delConditionalWaylineJob(String jobId);

    Boolean addPrepareConditionalWaylineJob(WaylineJobDTO waylineJob);

    Optional<ConditionalWaylineJobKey> getNearestConditionalWaylineJob();

    Double getConditionalWaylineJobTime(ConditionalWaylineJobKey jobKey);

    Boolean removePrepareConditionalWaylineJob(ConditionalWaylineJobKey jobKey);

    void setExcutingWaylineJob(WaylineJobDTO waylineJobDTO);

    boolean getDockFlyCMD(@NotBlank String dockSn);

    void setDockFlyCMD(@NotBlank String dockSn);
    void setPointToJob(Point point, String jobId);
    Point getPointToJob(String jobId);
    void flushPointToJob(String jobId);
}
