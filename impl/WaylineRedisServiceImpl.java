package com.dji.zc.cloud.wayline.service.impl;

import com.dji.sdk.cloudapi.control.Point;
import com.dji.sdk.cloudapi.wayline.FlighttaskProgress;
import com.dji.zc.cloud.component.mqtt.model.EventsReceiver;
import com.dji.zc.cloud.component.redis.RedisConst;
import com.dji.zc.cloud.component.redis.RedisOpsUtils;
import com.dji.zc.cloud.wayline.model.dto.ConditionalWaylineJobKey;
import com.dji.zc.cloud.wayline.model.dto.WaylineJobDTO;
import com.dji.zc.cloud.wayline.service.IWaylineRedisService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * @author sean
 * @version 1.4
 * @date 2023/3/24
 */
@Service
public class WaylineRedisServiceImpl implements IWaylineRedisService {

    @Override
    public void setRunningWaylineJob(String dockSn, EventsReceiver<FlighttaskProgress> data) {
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_JOB_RUNNING_PREFIX + dockSn, data, 60);
    }

    @Override
    public void setRunningJobDTO(String dockSn, WaylineJobDTO data) {
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_RUNNING_JOB_DTO_PREFIX + dockSn, data, RedisConst.DRC_MODE_ALIVE_SECOND);
    }

    @Override
    public Optional<WaylineJobDTO>  getRunningJob(String dockSn) {
        return Optional.ofNullable((WaylineJobDTO) RedisOpsUtils.get(RedisConst.WAYLINE_RUNNING_JOB_DTO_PREFIX + dockSn));
    }

    @Override
    public Optional<EventsReceiver<FlighttaskProgress>> getRunningWaylineJob(String dockSn) {
        return Optional.ofNullable((EventsReceiver<FlighttaskProgress>) RedisOpsUtils.get(RedisConst.WAYLINE_JOB_RUNNING_PREFIX + dockSn));
    }

    @Override
    public Boolean delRunningWaylineJob(String dockSn) {
        return RedisOpsUtils.del(RedisConst.WAYLINE_JOB_RUNNING_PREFIX + dockSn);
    }
    @Override
    public void delRunningJobDTO(String dockSn) {
        RedisOpsUtils.del(RedisConst.WAYLINE_RUNNING_JOB_DTO_PREFIX + dockSn);
    }

    @Override
    public void setPausedWaylineJob(String dockSn, String jobId) {
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_JOB_PAUSED_PREFIX + dockSn, jobId, RedisConst.DRC_MODE_ALIVE_SECOND);
    }

    @Override
    public String getPausedWaylineJobId(String dockSn) {
        return (String) RedisOpsUtils.get(RedisConst.WAYLINE_JOB_PAUSED_PREFIX + dockSn);
    }

    @Override
    public Boolean delPausedWaylineJob(String dockSn) {
        return RedisOpsUtils.del(RedisConst.WAYLINE_JOB_PAUSED_PREFIX + dockSn);
    }

    @Override
    public void setBlockedWaylineJob(String dockSn, String jobId) {
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_JOB_BLOCK_PREFIX + dockSn, jobId, RedisConst.WAYLINE_JOB_BLOCK_TIME);
    }

    @Override
    public String getBlockedWaylineJobId(String dockSn) {
        return (String) RedisOpsUtils.get(RedisConst.WAYLINE_JOB_BLOCK_PREFIX + dockSn);
    }

    @Override
    public void setConditionalWaylineJob(WaylineJobDTO waylineJob) {
        if (!StringUtils.hasText(waylineJob.getJobId())) {
            throw new RuntimeException("Job id can't be null.");
        }
        RedisOpsUtils.setWithExpire(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + waylineJob.getJobId(), waylineJob,
                (Duration.between(waylineJob.getEndTime(), LocalDateTime.now()).getSeconds()));
    }

    @Override
    public Optional<WaylineJobDTO> getConditionalWaylineJob(String jobId) {
        return Optional.ofNullable((WaylineJobDTO) RedisOpsUtils.get(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + jobId));
    }

    @Override
    public Boolean delConditionalWaylineJob(String jobId) {
        return RedisOpsUtils.del(RedisConst.WAYLINE_JOB_CONDITION_PREFIX + jobId);
    }

    @Override
    public Boolean addPrepareConditionalWaylineJob(WaylineJobDTO waylineJob) {
        if (Objects.isNull(waylineJob.getBeginTime())) {
            return false;
        }
        // value: {workspace_id}:{dock_sn}:{job_id}
        return RedisOpsUtils.zAdd(RedisConst.WAYLINE_JOB_CONDITION_PREPARE,
                waylineJob.getWorkspaceId() + RedisConst.DELIMITER + waylineJob.getDockSn() + RedisConst.DELIMITER + waylineJob.getJobId(),
                waylineJob.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    @Override
    public Optional<ConditionalWaylineJobKey> getNearestConditionalWaylineJob() {
        return Optional.ofNullable(RedisOpsUtils.zGetMin(RedisConst.WAYLINE_JOB_CONDITION_PREPARE))
                .map(Object::toString).map(ConditionalWaylineJobKey::new);
    }

    @Override
    public Double getConditionalWaylineJobTime(ConditionalWaylineJobKey jobKey) {
        return RedisOpsUtils.zScore(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, jobKey.getKey());
    }

    @Override
    public Boolean removePrepareConditionalWaylineJob(ConditionalWaylineJobKey jobKey) {
        return RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_CONDITION_PREPARE, jobKey.getKey());
    }

    @Override
    public void setExcutingWaylineJob(WaylineJobDTO waylineJobDTO) {
        return;
    }

    @Override
    public boolean getDockFlyCMD(String dockSn) {
        return RedisOpsUtils.checkExist(RedisConst.FLY_CMD_PREFIX +RedisConst.DELIMITER+ dockSn);
    }
    @Override
    public void setDockFlyCMD(String dockSn){
        RedisOpsUtils.setWithExpire(RedisConst.TAKE_OFF_TO_POINT + dockSn,true,10);
    }

    @Override
    public void setPointToJob(Point point,String jobId){
        RedisOpsUtils.setWithExpire(RedisConst.TAKE_OFF_TO_POINT + jobId,point,2000);
    }

    @Override
    public Point getPointToJob(String jobId){
        return (Point) RedisOpsUtils.get(RedisConst.TAKE_OFF_TO_POINT + jobId);
    }
    @Override
    public void flushPointToJob(String jobId) {
        RedisOpsUtils.expireKey(RedisConst.TAKE_OFF_TO_POINT + jobId,2000);
    }
}
