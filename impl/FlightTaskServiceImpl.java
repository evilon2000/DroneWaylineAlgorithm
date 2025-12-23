package com.dji.zc.cloud.wayline.service.impl;

import com.dji.sdk.cloudapi.device.ExitWaylineWhenRcLostEnum;
import com.dji.sdk.cloudapi.media.UploadFlighttaskMediaPrioritize;
import com.dji.sdk.cloudapi.media.api.AbstractMediaService;
import com.dji.sdk.cloudapi.wayline.*;
import com.dji.sdk.cloudapi.wayline.api.AbstractWaylineService;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.Pagination;
import com.dji.sdk.common.PaginationData;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.mqtt.MqttReply;
import com.dji.sdk.mqtt.events.EventsDataRequest;
import com.dji.sdk.mqtt.events.TopicEventsRequest;
import com.dji.sdk.mqtt.events.TopicEventsResponse;
import com.dji.sdk.mqtt.services.ServicesReplyData;
import com.dji.sdk.mqtt.services.TopicServicesResponse;
import com.dji.zc.cloud.common.error.CommonErrorEnum;
import com.dji.zc.cloud.common.model.CustomClaim;
import com.dji.zc.cloud.common.util.UserDataUtils;
import com.dji.zc.cloud.component.mqtt.model.EventsReceiver;
import com.dji.zc.cloud.component.redis.RedisConst;
import com.dji.zc.cloud.component.redis.RedisOpsUtils;
import com.dji.zc.cloud.manage.model.dto.DeviceDTO;
import com.dji.zc.cloud.manage.service.IDeviceRedisService;
import com.dji.zc.cloud.media.model.MediaFileCountDTO;
import com.dji.zc.cloud.wayline.model.dto.ConditionalWaylineJobKey;
import com.dji.zc.cloud.wayline.model.dto.PendingWaylineDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineJobDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineTaskConditionDTO;
import com.dji.zc.cloud.wayline.model.entity.FlightTrackEntity;
import com.dji.zc.cloud.wayline.model.enums.WaylineErrorCodeEnum;
import com.dji.zc.cloud.wayline.model.enums.WaylineJobStatusEnum;
import com.dji.zc.cloud.wayline.model.param.CreateJobParam;
import com.dji.zc.cloud.wayline.model.param.FlyJobParam;
import com.dji.zc.cloud.wayline.model.param.UpdateJobParam;
import com.dji.zc.cloud.wayline.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpStatus;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.net.URL;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * @author sean
 * @version 1.1
 */
@Service
@Slf4j
public class FlightTaskServiceImpl extends AbstractWaylineService implements IFlightTaskService {

    @Resource
    private IWaylineJobService waylineJobService;

    @Resource
    private IDeviceRedisService deviceRedisService;

    @Resource
    private IWaylineRedisService waylineRedisService;

    @Resource
    private IFlightTaskBreakpointService flightTaskBreakpointService;

    @Resource
    private IWaylineFileService waylineFileService;

    @Resource
    private SDKWaylineService abstractWaylineService;

    @Resource
    private IFlightTrackService flightTrackService;

    @Resource
    @Qualifier("mediaServiceImpl")
    private AbstractMediaService abstractMediaService;

    @Scheduled(initialDelay = 10, fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void checkScheduledJob() {
        Object jobIdValue = RedisOpsUtils.zGetMin(RedisConst.WAYLINE_JOB_TIMED_EXECUTE);
        if (Objects.isNull(jobIdValue)) {
            return;
        }
        log.info("Check the timed tasks of the wayline. {}", jobIdValue);
        // format: {workspace_id}:{dock_sn}:{job_id}
        String[] jobArr = String.valueOf(jobIdValue).split(RedisConst.DELIMITER);
        double time = RedisOpsUtils.zScore(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
        long now = System.currentTimeMillis();
        int offset = 30_000;

        // Expired tasks are deleted directly.
        if (time < now - offset) {
            RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
            waylineJobService.updateJob(WaylineJobDTO.builder()
                    .jobId(jobArr[2])
                    .status(WaylineJobStatusEnum.FAILED.getVal())
                    .executeTime(LocalDateTime.now())
                    .completedTime(LocalDateTime.now())
                    .code(HttpStatus.SC_REQUEST_TIMEOUT).build());
            return;
        }

        if (now <= time && time <= now + offset) {
            try {
                this.executeFlightTask(jobArr[0], jobArr[2]);
            } catch (Exception e) {
                log.info("The scheduled task delivery failed.");
                waylineJobService.updateJob(WaylineJobDTO.builder()
                        .jobId(jobArr[2])
                        .status(WaylineJobStatusEnum.FAILED.getVal())
                        .executeTime(LocalDateTime.now())
                        .completedTime(LocalDateTime.now())
                        .code(HttpStatus.SC_INTERNAL_SERVER_ERROR).build());
            } finally {
                RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, jobIdValue);
            }
        }
    }

    @Scheduled(initialDelay = 10, fixedRate = 5, timeUnit = TimeUnit.SECONDS)
    public void prepareConditionJob() {
        Optional<ConditionalWaylineJobKey> jobKeyOpt = waylineRedisService.getNearestConditionalWaylineJob();
        if (jobKeyOpt.isEmpty()) {
            return;
        }
        ConditionalWaylineJobKey jobKey = jobKeyOpt.get();
        log.info("Check the conditional tasks of the wayline. {}", jobKey.toString());
        // format: {workspace_id}:{dock_sn}:{job_id}
        double time = waylineRedisService.getConditionalWaylineJobTime(jobKey);
        long now = System.currentTimeMillis();
        // prepare the task one day in advance.
        int offset = 86_400_000;

        if (now + offset < time) {
            return;
        }

        WaylineJobDTO job = WaylineJobDTO.builder()
                .jobId(jobKey.getJobId())
                .status(WaylineJobStatusEnum.FAILED.getVal())
                .executeTime(LocalDateTime.now())
                .completedTime(LocalDateTime.now())
                .code(HttpStatus.SC_INTERNAL_SERVER_ERROR).build();
        try {
            Optional<WaylineJobDTO> waylineJobOpt = waylineRedisService.getConditionalWaylineJob(jobKey.getJobId());
            if (waylineJobOpt.isEmpty()) {
                job.setCode(CommonErrorEnum.REDIS_DATA_NOT_FOUND.getCode());
                waylineJobService.updateJob(job);
                waylineRedisService.removePrepareConditionalWaylineJob(jobKey);
                return;
            }
            WaylineJobDTO waylineJob = waylineJobOpt.get();

            HttpResultResponse result = this.publishOneFlightTask(waylineJob);
            waylineRedisService.removePrepareConditionalWaylineJob(jobKey);

            if (HttpResultResponse.CODE_SUCCESS == result.getCode()) {
                return;
            }

            // If the end time is exceeded, no more retries will be made.
            waylineRedisService.delConditionalWaylineJob(jobKey.getJobId());
            if (waylineJob.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - RedisConst.WAYLINE_JOB_BLOCK_TIME * 1000 < now) {
                return;
            }

            // Retry if the end time has not been exceeded.
            this.retryPrepareJob(jobKey, waylineJob);

        } catch (Exception e) {
            log.info("Failed to prepare the conditional task.");
            waylineJobService.updateJob(job);
        }

    }

    @Override
    public TopicEventsResponse<MqttReply> flighttaskProgress(TopicEventsRequest<EventsDataRequest<FlighttaskProgress>> response, MessageHeaders headers) {
        return abstractWaylineService.flighttaskProgress(response, headers);
    }

    /**
     * For immediate tasks, the server time shall prevail.
     *
     * @param param
     */
    private void fillImmediateTime(CreateJobParam param) {
        if (TaskTypeEnum.IMMEDIATE != param.getTaskType()) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        param.setTaskDays(List.of(now));
        param.setTaskPeriods(List.of(List.of(now)));
    }


    private void addConditions(WaylineJobDTO waylineJob, CreateJobParam param, Long beginTime, Long endTime) {
        if (TaskTypeEnum.CONDITIONAL != param.getTaskType()) {
            return;
        }

        waylineJob.setConditions(
                WaylineTaskConditionDTO.builder()
                        .executableConditions(Objects.nonNull(param.getMinStorageCapacity()) ?
                                new ExecutableConditions().setStorageCapacity(param.getMinStorageCapacity()) : null)
                        .readyConditions(new ReadyConditions()
                                .setBatteryCapacity(param.getMinBatteryCapacity())
                                .setBeginTime(beginTime)
                                .setEndTime(endTime))
                        .build());

        waylineRedisService.setConditionalWaylineJob(waylineJob);
        // key: wayline_job_condition, value: {workspace_id}:{dock_sn}:{job_id}
        boolean isAdd = waylineRedisService.addPrepareConditionalWaylineJob(waylineJob);
        if (!isAdd) {
            throw new RuntimeException("Failed to create conditional job.");
        }
    }

    /**
     * 发布飞行任务
     *
     * @param param
     * @return
     * @throws SQLException
     */
    @Override
    public HttpResultResponse publishFlightTask(CreateJobParam param, String workSpaceId ) throws SQLException {
        fillImmediateTime(param);

        if (!CollectionUtils.isEmpty(param.getExecuteStartTimeArr()) && TaskTypeEnum.TIMED.equals(param.getTaskType())) {
            var batchId = UUID.randomUUID().toString();

            for (Long executeTime : param.getExecuteStartTimeArr()) {
                long exTime = Instant.ofEpochSecond(executeTime).
                        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

                if (exTime < System.currentTimeMillis()) {
                    continue;
                }
                Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.
                        createWaylineJob(param, workSpaceId, UserDataUtils.getUserData().getUserName(), exTime, exTime, batchId);
                if (waylineJobOpt.isEmpty()) {
                    throw new SQLException("Failed to create wayline job.");
                }
                WaylineJobDTO waylineJob = waylineJobOpt.get();

                addConditions(waylineJob, param, exTime, exTime);
                HttpResultResponse response = this.publishOneFlightTask(waylineJob);
                if (HttpResultResponse.CODE_SUCCESS != response.getCode()) {
                    return response;
                }
            }
            return HttpResultResponse.success();
        }
        for (Long taskDay : param.getTaskDays()) {
            LocalDate date = LocalDate.ofInstant(Instant.ofEpochSecond(taskDay), ZoneId.systemDefault());
            for (List<Long> taskPeriod : param.getTaskPeriods()) {
                long beginTime = LocalDateTime.of(date, LocalTime.ofInstant(Instant.ofEpochSecond(taskPeriod.get(0)), ZoneId.systemDefault()))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long endTime = taskPeriod.size() > 1 ?
                        LocalDateTime.of(date, LocalTime.ofInstant(Instant.ofEpochSecond(taskPeriod.get(1)), ZoneId.systemDefault()))
                                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : beginTime;

                if (TaskTypeEnum.IMMEDIATE != param.getTaskType() && endTime < System.currentTimeMillis()) {
                    continue;
                }
                if(waylineRedisService.getDockFlyCMD(param.getDockSn())){
                    return HttpResultResponse.error("已有飞行任务下发,请等待10秒再次尝试");
                }
                waylineRedisService.setDockFlyCMD(param.getDockSn());

                if(waylineRedisService.getRunningWaylineJob(param.getDockSn()).isPresent()){
                    return HttpResultResponse.error("已有飞行中的任务,请等待飞行任务完成");
                }

                Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.createWaylineJob(param, workSpaceId, UserDataUtils.getUserData().getUserName(), beginTime, endTime);
                if (waylineJobOpt.isEmpty()) {
                    throw new SQLException("Failed to create wayline job.");
                }
                WaylineJobDTO waylineJob = waylineJobOpt.get();
                // If it is a conditional task type, add conditions to the job parameters.
                addConditions(waylineJob, param, beginTime, endTime);

                HttpResultResponse response = this.publishOneFlightTask(waylineJob);
                if (HttpResultResponse.CODE_SUCCESS != response.getCode()) {
                    return response;
                }
            }
        }
        return HttpResultResponse.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean flightTaskExecute(CreateJobParam param,String workSpaceId ,CustomClaim customClaim) throws SQLException {

        Long beginTime = System.currentTimeMillis();
        Long endTime = System.currentTimeMillis();
        Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.createWaylineJob(param, workSpaceId, customClaim.getUsername(), beginTime, endTime);
        if (waylineJobOpt.isEmpty()) {
            throw new SQLException("Failed to create wayline job.");
        }
        WaylineJobDTO waylineJob = waylineJobOpt.get();
        HttpResultResponse response = this.publishOneFlightTask(waylineJob);
        if (HttpResultResponse.CODE_SUCCESS != response.getCode()) {
            return false;
        }
        return true;
    }

    /**
     * 再次执行飞行任务
     *
     * @param param
     */
    @Override
    public void publishFlightTaskAgain(FlyJobParam param,String workSpaceId) {
        var existJob = waylineJobService.getJobByJobId(workSpaceId, param.getJobId());
        if (existJob.isEmpty()) {
            throw new RuntimeException("未找到执行任务信息");
        }

        var createParam = new CreateJobParam();
        createParam.setName(existJob.get().getJobName() + "reFly");
        createParam.setRthAltitude(existJob.get().getRthAltitude());
        createParam.setTaskType(TaskTypeEnum.IMMEDIATE);
        createParam.setDockSn(existJob.get().getDockSn());
        createParam.setFileId(existJob.get().getFileId());
        createParam.setOutOfControlAction(existJob.get().getOutOfControlAction());
        createParam.setWaylineType(existJob.get().getWaylineType());
        try {
            publishFlightTask(createParam, workSpaceId );
        } catch (SQLException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * 断点续飞
     */
    @Override
    public void retryFlightTaskAgain(FlyJobParam param,String workSpaceId , boolean fromBreakPoint) {
        if (!fromBreakPoint) {
            publishFlightTaskAgain(param, workSpaceId );
        } else {
            publishBreakPointTask(param,workSpaceId );
        }
    }

    private void publishBreakPointTask(FlyJobParam param,String workSpaceId ) {
        var breakPoint = flightTaskBreakpointService.getByJobId(workSpaceId, param.getJobId());
        if (breakPoint == null) {
            throw new RuntimeException("未找到任务断点");
        }
        var existJob = waylineJobService.getJobByJobId(workSpaceId, param.getJobId());
        if (existJob.isEmpty()) {
            throw new RuntimeException("未找到执行任务信息");
        }
        boolean isOnline = deviceRedisService.checkDeviceOnline(existJob.get().getDockSn());
        if (!isOnline) {
            throw new RuntimeException("机场离线");
        }
        var createParam = new CreateJobParam();
        createParam.setName(existJob.get().getJobName() + "_fromBreakPoint");
        createParam.setRthAltitude(existJob.get().getRthAltitude());
        createParam.setTaskType(TaskTypeEnum.IMMEDIATE);
        createParam.setDockSn(existJob.get().getDockSn());
        createParam.setFileId(existJob.get().getFileId());
        createParam.setOutOfControlAction(existJob.get().getOutOfControlAction());
        createParam.setWaylineType(existJob.get().getWaylineType());

        Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.createWaylineJob(createParam, workSpaceId, UserDataUtils.getUserData().getUserName(),
                System.currentTimeMillis() , System.currentTimeMillis() );
        if (waylineJobOpt.isEmpty()) {
            throw new RuntimeException("创建航线任务失败");
        }
        WaylineJobDTO waylineJob = waylineJobOpt.get();
        waylineJob.setBeginTime(LocalDateTime.now());
        FlighttaskBreakPoint flighttaskBreakPoint = new FlighttaskBreakPoint();
        flighttaskBreakPoint.setIndex(breakPoint.getSeq());
        flighttaskBreakPoint.setProgress(breakPoint.getProgress());
        flighttaskBreakPoint.setState(breakPoint.getState());
        flighttaskBreakPoint.setWaylineId(breakPoint.getWaylineId());

        boolean isSuccess = false;
        try {
            isSuccess = this.prepareFlightTask(waylineJob, flighttaskBreakPoint);
        } catch (SQLException e) {
            log.error(e.getMessage());
        }
        if (!isSuccess) {
            throw new RuntimeException("任务准备失败");
        }
        if (!executeFlightTask(waylineJob.getWorkspaceId(), waylineJob.getJobId())) {
            throw new RuntimeException("任务执行失败");
        }
    }

    /**
     * 发布一键起飞任务
     *
     * @param waylineJob
     * @return
     * @throws SQLException
     */
    public HttpResultResponse publishOneFlightTask(WaylineJobDTO waylineJob) throws SQLException {
        boolean isOnline = deviceRedisService.checkDeviceOnline(waylineJob.getDockSn());
        if (!isOnline) {
            throw new RuntimeException("机场离线");
        }

        boolean isSuccess = this.prepareFlightTask(waylineJob, null);
        if (!isSuccess) {
            return HttpResultResponse.error("任务准备失败");
        }

        // Issue an immediate task execution command.
        if (TaskTypeEnum.IMMEDIATE == waylineJob.getTaskType()) {
            if (!executeFlightTask(waylineJob.getWorkspaceId(), waylineJob.getJobId())) {
                return HttpResultResponse.error("任务执行失败");
            }
        }

        if (TaskTypeEnum.TIMED == waylineJob.getTaskType()) {
            // key: wayline_job_timed, value: {workspace_id}:{dock_sn}:{job_id}
            boolean isAdd = RedisOpsUtils.zAdd(RedisConst.WAYLINE_JOB_TIMED_EXECUTE,
                    waylineJob.getWorkspaceId() + RedisConst.DELIMITER + waylineJob.getDockSn() + RedisConst.DELIMITER + waylineJob.getJobId(),
                    waylineJob.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            if (!isAdd) {
                return HttpResultResponse.error("创建定期任务失败");
            }
        }
        Optional<DeviceDTO> dockOpt = deviceRedisService.getDeviceOnline(waylineJob.getDockSn());
        if (dockOpt.isEmpty() || !StringUtils.hasText(dockOpt.get().getChildDeviceSn())) {
            throw new RuntimeException("无人机不在线");
        }

        return HttpResultResponse.success();
    }

    private Boolean prepareFlightTask(WaylineJobDTO waylineJob, FlighttaskBreakPoint breakPoint) throws SQLException {
        // get wayline file
        Optional<GetWaylineListResponse> waylineFile = waylineFileService.getWaylineByWaylineId(waylineJob.getWorkspaceId(), waylineJob.getFileId());
        if (waylineFile.isEmpty()) {
            throw new SQLException("航线不存在");
        }

        // get file url
        URL url = waylineFileService.getObjectUrl(waylineJob.getWorkspaceId(), waylineFile.get().getId());

        FlighttaskPrepareRequest flightTask = new FlighttaskPrepareRequest()
                .setFlightId(waylineJob.getJobId())
                .setExecuteTime(waylineJob.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .setTaskType(waylineJob.getTaskType())
                .setWaylineType(waylineJob.getWaylineType())
                .setRthAltitude(waylineJob.getRthAltitude())
                .setOutOfControlAction(waylineJob.getOutOfControlAction())
                .setExitWaylineWhenRcLost(ExitWaylineWhenRcLostEnum.EXECUTE_RC_LOST_ACTION)
                .setFile(new FlighttaskFile()
                        .setUrl(url.toString())
                        .setFingerprint(waylineFile.get().getSign()));

        if (breakPoint != null) {
            flightTask.setBreakPoint(breakPoint);
        }

        if (TaskTypeEnum.CONDITIONAL == waylineJob.getTaskType()) {
            if (Objects.isNull(waylineJob.getConditions())) {
                throw new IllegalArgumentException();
            }
            flightTask.setReadyConditions(waylineJob.getConditions().getReadyConditions());
            flightTask.setExecutableConditions(waylineJob.getConditions().getExecutableConditions());
        }

        TopicServicesResponse<ServicesReplyData> serviceReply = abstractWaylineService.flighttaskPrepare(
                SDKManager.getDeviceSDK(waylineJob.getDockSn()), flightTask);
        if (!serviceReply.getData().getResult().isSuccess()) {
            log.info("Prepare task ====> Error code: {}", serviceReply.getData().getResult());
            waylineJobService.updateJob(WaylineJobDTO.builder()
                    .workspaceId(waylineJob.getWorkspaceId())
                    .jobId(waylineJob.getJobId())
                    .executeTime(LocalDateTime.now())
                    .status(WaylineJobStatusEnum.FAILED.getVal())
                    .completedTime(LocalDateTime.now())
                    .code(serviceReply.getData().getResult().getCode()).build());
            return false;
        }
        return true;
    }


    @Override
    public Boolean executeFlightTask(String workspaceId, String jobId) {
        // get job
        Optional<WaylineJobDTO> waylineJob = waylineJobService.getJobByJobId(workspaceId, jobId);
        if (waylineJob.isEmpty()) {
            throw new IllegalArgumentException("任务不存在,请检查任务ID");
        }

        boolean isOnline = deviceRedisService.checkDeviceOnline(waylineJob.get().getDockSn());
        if (!isOnline) {
            throw new RuntimeException("机场离线");
        }

        WaylineJobDTO job = waylineJob.get();
        var hasRunningJob = waylineRedisService.getRunningWaylineJob(job.getDockSn()).isPresent();
        if (hasRunningJob) {
            throw new RuntimeException("已有执行中的任务,稍后再试");
        }

        TopicServicesResponse<ServicesReplyData> serviceReply = abstractWaylineService.flighttaskExecute(
                SDKManager.getDeviceSDK(job.getDockSn()), new FlighttaskExecuteRequest().setFlightId(jobId));
        if (!serviceReply.getData().getResult().isSuccess()) {
            log.info("Execute job ====> Error: {}", serviceReply.getData().getResult());
            waylineJobService.updateJob(WaylineJobDTO.builder()
                    .jobId(jobId)
                    .executeTime(LocalDateTime.now())
                    .status(WaylineJobStatusEnum.FAILED.getVal())
                    .completedTime(LocalDateTime.now())
                    .code(serviceReply.getData().getResult().getCode()).build());
            // The conditional task fails and enters the blocking status.
            if (TaskTypeEnum.CONDITIONAL == job.getTaskType()
                    && WaylineErrorCodeEnum.find(serviceReply.getData().getResult().getCode()).isBlock()) {
                waylineRedisService.setBlockedWaylineJob(job.getDockSn(), jobId);
            }
            return false;
        }

        waylineRedisService.setRunningWaylineJob(job.getDockSn(), EventsReceiver.<FlighttaskProgress>builder().bid(jobId).sn(job.getDockSn()).build());
        waylineJobService.updateJob(WaylineJobDTO.builder()
                .jobId(jobId)
                .executeTime(LocalDateTime.now())
                .status(WaylineJobStatusEnum.IN_PROGRESS.getVal())
                .build());
        waylineRedisService.setRunningJobDTO(job.getDockSn(), waylineJobService.getJobBySimpleJobId(jobId).get());
        return true;
    }

    @Override
    public void cancelFlightTask(String workspaceId, Collection<String> jobIds) {
        List<WaylineJobDTO> waylineJobs = waylineJobService.getJobsByConditions(workspaceId, jobIds, WaylineJobStatusEnum.PENDING);

        Set<String> waylineJobIds = waylineJobs.stream().map(WaylineJobDTO::getJobId).collect(Collectors.toSet());
        // Check if the task status is correct.
        boolean isErr = !jobIds.removeAll(waylineJobIds) || !jobIds.isEmpty();
        if (isErr) {
            throw new IllegalArgumentException("当前任务状态无法删除任务" + Arrays.toString(jobIds.toArray()));
        }

        // Group job id by dock sn.
        Map<String, List<String>> dockJobs = waylineJobs.stream()
                .collect(Collectors.groupingBy(WaylineJobDTO::getDockSn,
                        Collectors.mapping(WaylineJobDTO::getJobId, Collectors.toList())));
        dockJobs.forEach((dockSn, idList) -> this.publishCancelTask(workspaceId, dockSn, idList));

    }

    public void publishCancelTask(String workspaceId, String dockSn, List<String> jobIds) {
        boolean isOnline = deviceRedisService.checkDeviceOnline(dockSn);
        if (!isOnline) {
            throw new RuntimeException("机场掉线了");
        }

        TopicServicesResponse<ServicesReplyData> serviceReply = abstractWaylineService.flighttaskUndo(SDKManager.getDeviceSDK(dockSn),
                new FlighttaskUndoRequest().setFlightIds(jobIds));
        if (!serviceReply.getData().getResult().isSuccess()) {
            log.info("Cancel job ====> Error: {}", serviceReply.getData().getResult());
            throw new RuntimeException("Failed to cancel the wayline job of " + dockSn);
        }

        for (String jobId : jobIds) {
            waylineJobService.updateJob(WaylineJobDTO.builder()
                    .workspaceId(workspaceId)
                    .jobId(jobId)
                    .status(WaylineJobStatusEnum.CANCEL.getVal())
                    .completedTime(LocalDateTime.now())
                    .build());
            RedisOpsUtils.zRemove(RedisConst.WAYLINE_JOB_TIMED_EXECUTE, workspaceId + RedisConst.DELIMITER + dockSn + RedisConst.DELIMITER + jobId);
        }

    }

    @Override
    public void uploadMediaHighestPriority(String workspaceId, String jobId) {
        Optional<WaylineJobDTO> jobOpt = waylineJobService.getJobByJobId(workspaceId, jobId);
        if (jobOpt.isEmpty()) {
            throw new RuntimeException(CommonErrorEnum.ILLEGAL_ARGUMENT.getMessage());
        }

        String dockSn = jobOpt.get().getDockSn();
        String key = RedisConst.MEDIA_HIGHEST_PRIORITY_PREFIX + dockSn;
        if (RedisOpsUtils.checkExist(key) && jobId.equals(((MediaFileCountDTO) RedisOpsUtils.get(key)).getJobId())) {
            return;
        }

        TopicServicesResponse<ServicesReplyData> reply = abstractMediaService.uploadFlighttaskMediaPrioritize(
                SDKManager.getDeviceSDK(dockSn), new UploadFlighttaskMediaPrioritize().setFlightId(jobId));
        if (!reply.getData().getResult().isSuccess()) {
            throw new RuntimeException("Failed to set media job upload priority. Error: " + reply.getData().getResult());
        }
    }

    @Override
    public void updateJobStatus(String workspaceId, String jobId, UpdateJobParam param) {
        Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.getJobByJobId(workspaceId, jobId);
        if (waylineJobOpt.isEmpty()) {
            throw new RuntimeException("未找到本次航线任务信息");
        }
        WaylineJobDTO waylineJob = waylineJobOpt.get();
        WaylineJobStatusEnum statusEnum = waylineJobService.getWaylineState(waylineJob.getDockSn());
        if (statusEnum.getEnd() || WaylineJobStatusEnum.PENDING == statusEnum) {
            throw new RuntimeException("航线任务未启动或者已结束");
        }

        switch (param.getStatus()) {
            case PAUSE:
                pauseJob(workspaceId, waylineJob.getDockSn(), jobId, statusEnum);
                break;
            case RESUME:
                resumeJob(workspaceId, waylineJob.getDockSn(), jobId, statusEnum);
                break;
        }

    }

    //暂停飞行任务
    private void pauseJob(String workspaceId, String dockSn, String jobId, WaylineJobStatusEnum statusEnum) {
        if (WaylineJobStatusEnum.PAUSED == statusEnum && jobId.equals(waylineRedisService.getPausedWaylineJobId(dockSn))) {
            waylineRedisService.setPausedWaylineJob(dockSn, jobId);
            return;
        }

        TopicServicesResponse<ServicesReplyData> reply = abstractWaylineService.flighttaskPause(SDKManager.getDeviceSDK(dockSn));
        if (!reply.getData().getResult().isSuccess()) {
            throw new RuntimeException("Failed to pause wayline job. Error: " + reply.getData().getResult());
        }
        waylineRedisService.delRunningWaylineJob(dockSn);
        waylineRedisService.setPausedWaylineJob(dockSn, jobId);
    }

    private void resumeJob(String workspaceId, String dockSn, String jobId, WaylineJobStatusEnum statusEnum) {
        Optional<EventsReceiver<FlighttaskProgress>> runningDataOpt = waylineRedisService.getRunningWaylineJob(dockSn);
        if (WaylineJobStatusEnum.IN_PROGRESS == statusEnum && jobId.equals(runningDataOpt.map(EventsReceiver::getSn).get())) {
            waylineRedisService.setRunningWaylineJob(dockSn, runningDataOpt.get());
            return;
        }
        TopicServicesResponse<ServicesReplyData> reply = abstractWaylineService.flighttaskRecovery(SDKManager.getDeviceSDK(dockSn));
        if (!reply.getData().getResult().isSuccess()) {
            throw new RuntimeException("Failed to resume wayline job. Error: " + reply.getData().getResult());
        }

        runningDataOpt.ifPresent(runningData -> waylineRedisService.setRunningWaylineJob(dockSn, runningData));
        waylineRedisService.delPausedWaylineJob(dockSn);
    }

    @Override
    public void retryPrepareJob(ConditionalWaylineJobKey jobKey, WaylineJobDTO waylineJob) {
        if(waylineRedisService.getDockFlyCMD(waylineJob.getDockSn())){
            throw new RuntimeException("已有飞行任务下发,请等待10秒再次尝试");
        }
        waylineRedisService.setDockFlyCMD(waylineJob.getDockSn());


        Optional<WaylineJobDTO> childJobOpt = waylineJobService.createWaylineJobByParent(jobKey.getWorkspaceId(), jobKey.getJobId());
        if (childJobOpt.isEmpty()) {
            log.error("创建航线任务失败");
            return;
        }

        WaylineJobDTO newJob = childJobOpt.get();
        newJob.setBeginTime(LocalDateTime.now().plusSeconds(RedisConst.WAYLINE_JOB_BLOCK_TIME));
        boolean isAdd = waylineRedisService.addPrepareConditionalWaylineJob(newJob);
        if (!isAdd) {
            log.error("Failed to create wayline job. {}", newJob.getJobId());
            return;
        }

        waylineJob.setJobId(newJob.getJobId());
        waylineRedisService.setConditionalWaylineJob(waylineJob);
    }

    @Override
    public List<FlightTrackEntity> getJobTrackByJobId(String workspaceId, String jobId) {

        return flightTrackService.getAllFlightTrackByJobId(jobId, workspaceId);
    }


    @Override
    public TopicEventsResponse<MqttReply> flighttaskReady(TopicEventsRequest<FlighttaskReady> response, MessageHeaders headers) {
        List<String> flightIds = response.getData().getFlightIds();

        log.info("ready task list：{}", Arrays.toString(flightIds.toArray()));
        // Check conditional task blocking status.
        String blockedId = waylineRedisService.getBlockedWaylineJobId(response.getGateway());
        if (!StringUtils.hasText(blockedId)) {
            return null;
        }

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(response.getGateway());
        if (deviceOpt.isEmpty()) {
            return null;
        }
        DeviceDTO device = deviceOpt.get();

        try {
            for (String jobId : flightIds) {
                boolean isExecute = this.executeFlightTask(device.getWorkspaceId(), jobId);
                if (!isExecute) {
                    return null;
                }
                Optional<WaylineJobDTO> waylineJobOpt = waylineRedisService.getConditionalWaylineJob(jobId);
                if (waylineJobOpt.isEmpty()) {
                    log.info("The conditional job has expired and will no longer be executed.");
                    return new TopicEventsResponse<>();
                }
                WaylineJobDTO waylineJob = waylineJobOpt.get();
                this.retryPrepareJob(new ConditionalWaylineJobKey(device.getWorkspaceId(), response.getGateway(), jobId), waylineJob);
                return new TopicEventsResponse<>();
            }
        } catch (Exception e) {
            log.error("Failed to execute conditional task.");
            e.printStackTrace();
        }
        return new TopicEventsResponse<>();
    }

    @Override
    public PaginationData<PendingWaylineDTO> getPendingWaylineJob(String workspaceId, String dockSn, Long page, Long pageSize) {
//        var waylineJobs = waylineFileService.getWaylinesByParam()
//        var waylineJobs = 
        PaginationData<WaylineJobDTO> pageWaylineJobs = waylineJobService.getPendingWaylines(workspaceId, dockSn, page, pageSize);
        var waylineJobs = pageWaylineJobs.getList();

        var pendingWaylines = new ArrayList<PendingWaylineDTO>();

        for (WaylineJobDTO job : waylineJobs) {
            if (job.getBatchId() != null && job.getTaskType().equals(TaskTypeEnum.TIMED)) {
                if (pendingWaylines.stream().anyMatch(x -> x.getBatchId() != null && x.getBatchId().equals(job.getBatchId()))) {
                    var pendingWayline = pendingWaylines.stream().filter(x -> x.getBatchId() != null && x.getBatchId().equals(job.getBatchId())).findFirst().get();
                    pendingWayline.getJobIds().add(job.getJobId());
                    pendingWayline.getBeginTimes().add(job.getBeginTime());
                } else {
                    var pendingWayline = new PendingWaylineDTO();
                    var totalCount = waylineJobService.getJobCountByBatchId(job.getBatchId());
                    var leftCount = waylineJobs.stream().filter(x -> x.getBatchId() != null && x.getBatchId().equals(job.getBatchId())).count();
                    pendingWayline.setBeginTime(job.getBeginTime());
                    pendingWayline.setStatus(job.getStatus());
                    pendingWayline.setJobName(job.getJobName());
                    pendingWayline.setFileId(job.getFileId());
                    pendingWayline.setWaylineId(job.getFileId());
                    pendingWayline.setDeviceName(job.getDeviceName());
                    pendingWayline.setDockName(job.getDockName());
                    pendingWayline.setRthAltitude(job.getRthAltitude());
                    pendingWayline.setBatchId(job.getBatchId());
                    pendingWayline.setTotalJobCount(totalCount);
                    pendingWayline.setLeftJobCount(leftCount);
                    pendingWayline.setJobId(waylineJobs.get(0).getJobId());
                    pendingWayline.setDeviceName(job.getDeviceName());
                    pendingWaylines.add(pendingWayline);
                    var jobIds = new ArrayList<String>();
                    jobIds.add(job.getJobId());
                    pendingWayline.setJobIds(jobIds);
                    var beginTimes = new ArrayList<LocalDateTime>();
                    beginTimes.add(job.getBeginTime());
                    pendingWayline.setBeginTimes(beginTimes);
                }
            } else {
                var pendingWayline = getPendingWaylineDTO(job);
                pendingWaylines.add(pendingWayline);
            }
        }
        return  new PaginationData<PendingWaylineDTO>(pendingWaylines, new Pagination(pageWaylineJobs.getPagination().getPage(), pageWaylineJobs.getPagination().getPageSize(), pageWaylineJobs.getPagination().getTotal()));
    }

    private static @NotNull PendingWaylineDTO getPendingWaylineDTO(WaylineJobDTO job) {
        var pendingWayline = new PendingWaylineDTO();
        pendingWayline.setJobId(job.getJobId());
        pendingWayline.setFileId(job.getFileId());
        pendingWayline.setWaylineId(job.getFileId());
        pendingWayline.setBeginTime(job.getBeginTime());
        pendingWayline.setStatus(job.getStatus());
        pendingWayline.setJobName(job.getJobName());
        pendingWayline.setDeviceName(job.getDeviceName());
        pendingWayline.setDockName(job.getDockName());
        pendingWayline.setRthAltitude(job.getRthAltitude());
        pendingWayline.setBatchId(job.getBatchId());
        pendingWayline.setDeviceName(job.getDeviceName());
        return pendingWayline;
    }

    @Override
    public List<WaylineJobDTO> getRunningJob(String workspaceId, String dockSn) {
        var runningJob = waylineRedisService.getRunningWaylineJob(dockSn);
        if (runningJob.isEmpty()) {
            return List.of();
        }
        try {
            var jobId = runningJob.get().getOutput().getExt().getFlightId();
            var waylineJob = getRunningJobFromRedis(dockSn).stream().findFirst().orElse(new WaylineJobDTO());
            waylineJob.setWaylineId(waylineJob.getFileId());
            waylineJob.setProgress(runningJob.get().getOutput().getProgress().getPercent());
            return List.of(waylineJob);
        } catch (Exception e) {
            log.error(e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<WaylineJobDTO> getRunningJobFromRedis(String dockSn){
        Optional<WaylineJobDTO> runningJob = waylineRedisService.getRunningJob(dockSn);
        if(runningJob.isPresent()){
            return List.of(runningJob.get());
        }
        return List.of();
    }

    public PaginationData<WaylineJobDTO> getRunningJobFromRedisWithPage(String dockSn, Long page, Long pageSize){
        Optional<WaylineJobDTO> runningJob = waylineRedisService.getRunningJob(dockSn);
        if(runningJob.isPresent()){
            // 转换为列表（模拟分页数据源）
            List<WaylineJobDTO> jobs = runningJob.map(List::of).orElse(List.of());

            // 空或无效输入返回空分页
            if (jobs == null || page < 1 || pageSize <= 0) {
                return new PaginationData<>(Collections.emptyList(), new Pagination(page, pageSize, 0));
            }

            int total = jobs.size();
            // 转换为 0-based 索引
            int start = (int) ((page.intValue() - 1) * pageSize);
            // 超出范围或空列表返回空分页
            if (total == 0 || start >= total) {
                return new PaginationData<>(Collections.emptyList(), new Pagination(page, pageSize, total));
            }

            // 截取子列表
            List<WaylineJobDTO> pagedJobs = jobs.subList(start, (int)Math.min(start + pageSize, total));

            return new PaginationData<>(pagedJobs, new Pagination(page, pageSize, total));
        }
        return new PaginationData<WaylineJobDTO>(List.of(), new Pagination(page, pageSize, 0l));
    }
}
