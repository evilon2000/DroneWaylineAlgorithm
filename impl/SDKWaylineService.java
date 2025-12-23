package com.dji.zc.cloud.wayline.service.impl;

import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.wayline.*;
import com.dji.sdk.cloudapi.wayline.api.AbstractWaylineService;
import com.dji.sdk.cloudapi.wayline.model.dto.DroneDataDTO;
import com.dji.sdk.cloudapi.wayline.model.dto.DronePropertiesDTO;
import com.dji.sdk.common.Common;
import com.dji.sdk.mqtt.MqttReply;
import com.dji.sdk.mqtt.events.EventsDataRequest;
import com.dji.sdk.mqtt.events.TopicEventsRequest;
import com.dji.sdk.mqtt.events.TopicEventsResponse;
import com.dji.sdk.mqtt.requests.TopicRequestsRequest;
import com.dji.sdk.mqtt.requests.TopicRequestsResponse;
import com.dji.zc.cloud.common.error.CommonErrorEnum;
import com.dji.zc.cloud.component.mqtt.model.EventsReceiver;
import com.dji.zc.cloud.component.redis.RedisConst;
import com.dji.zc.cloud.component.redis.RedisOpsUtils;
import com.dji.zc.cloud.component.websocket.model.BizCodeEnum;
import com.dji.zc.cloud.component.websocket.service.IWebSocketMessageService;
import com.dji.zc.cloud.manage.model.dto.DeviceDTO;
import com.dji.zc.cloud.manage.model.dto.FlightTrackDTO;
import com.dji.zc.cloud.manage.model.enums.UserTypeEnum;
import com.dji.zc.cloud.manage.service.IDeviceRedisService;
import com.dji.zc.cloud.manage.service.IDeviceService;
import com.dji.zc.cloud.media.model.MediaFileCountDTO;
import com.dji.zc.cloud.media.service.IMediaRedisService;
import com.dji.zc.cloud.wayline.model.dto.WaylineJobDTO;
import com.dji.zc.cloud.wayline.model.entity.FlightStaticsEntity;
import com.dji.zc.cloud.wayline.model.entity.FlightTaskBreakpointEntity;
import com.dji.zc.cloud.wayline.model.entity.FlightTrackEntity;
import com.dji.zc.cloud.wayline.model.enums.WaylineJobStatusEnum;
import com.dji.zc.cloud.wayline.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.net.URL;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 */
@Service
@Slf4j
public class SDKWaylineService extends AbstractWaylineService {

    @Resource
    private IDeviceRedisService deviceRedisService;

    @Resource
    private IWaylineRedisService waylineRedisService;

    @Resource
    private IMediaRedisService mediaRedisService;

    @Resource
    private IWebSocketMessageService webSocketMessageService;

    @Resource
    private IWaylineJobService waylineJobService;

    @Resource
    private IWaylineFileService waylineFileService;

    @Resource
    private IFlightTrackService flightTrackService;

    @Resource
    private IDeviceService deviceService;
    @Resource
    private IFlightTaskBreakpointService flightTaskBreakpointService;
    @Resource
    private HttpServletRequest httpServletRequest;
    @Resource
    private IFlightStaticsService flightStaticsService;
    private static final float MIN_VALUE_THRESHOLD = 1e-5f;

    @Override
    public TopicEventsResponse<MqttReply> deviceExitHomingNotify(TopicEventsRequest<DeviceExitHomingNotify> request, MessageHeaders headers) {
        log.info(request.getData().toString());
        return new TopicEventsResponse<>();
    }

    @Override
    public TopicEventsResponse<MqttReply> track(TopicEventsRequest<DroneDataDTO> request, MessageHeaders headers) {
        DroneDataDTO droneData = Common.getObjectMapper().convertValue(request.getData(), DroneDataDTO.class);
        DronePropertiesDTO dronePropertiesDTO = droneData.getList().get(0);
        if (dronePropertiesDTO == null) {
            return new TopicEventsResponse<>();
        }
        if (!"flighttask_execute".equals(dronePropertiesDTO.getType())) {
            return new TopicEventsResponse<>();
        }

//        CustomClaim customClaim = (CustomClaim)httpServletRequest.getAttribute(TOKEN_CLAIM);

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(request.getGateway());
        if (deviceOpt.isEmpty()) {
            return new TopicEventsResponse<>();
        }
        String workspaceId = deviceService.getWorkspaceIdBySn(deviceOpt.get().getDeviceSn());
        Optional<OsdDockDrone> deviceOsd = deviceRedisService.getDeviceOsd(deviceOpt.get().getChildDeviceSn(), OsdDockDrone.class);
        if (deviceOsd.isEmpty()) {
            throw new RuntimeException("无人机不在线或未起飞");
        }
        FlightStaticsEntity flightStaticsEntity = FlightStaticsEntity.builder()
                .totalFlightDistance(deviceOsd.get().getTotalFlightDistance())
                .totalFlightTime(deviceOsd.get().getTotalFlightTime())
                .totalFlightSorties(deviceOsd.get().getTotalFlightSorties())
                .dockSn(request.getGateway())
                .workspaceId(workspaceId)
                .build();
        try {
            boolean insertFlightStatics = flightStaticsService.updateOrInsertFlightStatics(flightStaticsEntity);
        }catch (Exception e) {
            log.error("飞机面板信息统计失败"+e.getMessage());
        }

        String sn = dronePropertiesDTO.getSn();

        Set<Object> flightTrackJsons = RedisOpsUtils.zRange(RedisConst.FLIGHT_TRACK_PREFIX + sn, 0, -1);
        Set<FlightTrackDTO> flightTrackDTOS = flightTrackJsons.stream()
                .map(flightTrackJson -> {
                    try {
                        // 反序列化 JSON 字符串为 FlightTrackDTO 对象
                        return Common.getObjectMapper().convertValue(flightTrackJson, FlightTrackDTO.class);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null; // 如果出错，返回 null，可以根据需要处理异常
                    }
                })
                .filter(flightTrackDTO -> flightTrackDTO != null) // 过滤掉 null 对象
                .collect(Collectors.toSet());
        List<FlightTrackEntity> flightTrackEntities = flightTrackDTOS.stream().map(item -> {
            FlightTrackEntity flightTrack = FlightTrackEntity.builder()
                    .jobId(item.getFlightId())
                    .height(item.getHeight())
                    .latitude(item.getLatitude())
                    .longitude(item.getLongitude())
                    .elevation(item.getElevation())
                    .createTime(item.getCreateTime())
                    .workspaceId(workspaceId)
                    .build();
            return flightTrack;
        }).collect(Collectors.toList());
        Boolean isSave = flightTrackService.saveFlightTracks(flightTrackEntities);
        if (isSave) {
            RedisOpsUtils.del(RedisConst.FLIGHT_TRACK_PREFIX + sn);
        }
        return new TopicEventsResponse();
    }

    @Override
    public TopicEventsResponse<MqttReply> flighttaskProgress(TopicEventsRequest<EventsDataRequest<FlighttaskProgress>> response, MessageHeaders headers) {

        EventsReceiver<FlighttaskProgress> eventsReceiver = new EventsReceiver<>();
        eventsReceiver.setResult(response.getData().getResult());
        eventsReceiver.setOutput(response.getData().getOutput());
        eventsReceiver.setBid(response.getBid());
        eventsReceiver.setSn(response.getGateway());

        FlighttaskProgress output = eventsReceiver.getOutput();
        log.info("Task progress: {}", output.getProgress().toString());
        if (!eventsReceiver.getResult().isSuccess()) {
            log.error("Task progress ===> Error: " + eventsReceiver.getResult());
        }
        /**
         * 直播状态
         */
        if(output.getProgress().getCurrentStep().getStep() >= 24){

            var device = deviceRedisService.getDeviceOnline(response.getGateway()).get();
            var pushData = new HashMap<String, Object>();
            pushData.put("dock_sn",device.getDeviceSn());
            if(StringUtils.hasText(device.getChildDeviceSn())){
                pushData.put("drone_sn",device.getChildDeviceSn());
            }
            var droneOSD = deviceRedisService.getDeviceOsd(device.getChildDeviceSn(), OsdDockDrone.class);

            if(output.getProgress().getCurrentStep().getStep() >= 27 && (droneOSD.isEmpty() || droneOSD.get().getElevation() <= 0)) {
                pushData.put("start_push",false);
                deviceRedisService.setLiveStreamOffline(device.getDeviceSn());
            }else {
                pushData.put("start_push",true);
                deviceRedisService.setLiveStreamOnline(device.getDeviceSn());
            }
            deviceService.pushLiveStatusToWeb(device.getWorkspaceId(), BizCodeEnum.DRONE_LIVE_STATUS, device.getDeviceSn(), pushData);
        }

        FlighttaskProgressExt ext = response.getData().getOutput().getExt();
        if (ext == null) {
            log.error(" ext is null");
        }
        ProgressExtBreakPoint breakPoint = ext.getBreakPoint();
        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(response.getGateway());
        if (deviceOpt.isEmpty()) {
            return new TopicEventsResponse<>();
        }
        String workspaceId = deviceService.getWorkspaceIdBySn(deviceOpt.get().getDeviceSn());
        if (breakPoint != null) {
            FlightTaskBreakpointEntity flightTaskBreakpoint = FlightTaskBreakpointEntity.builder()
                    .seq(breakPoint.getIndex())
                    .jobId(ext.getFlightId())
                    .state(breakPoint.getState())
                    .attitudeHead(breakPoint.getAttitudeHead())
                    .breakReason(breakPoint.getBreakReason())
                    .progress(breakPoint.getProgress())
                    .waylineId(breakPoint.getWaylineId())
                    .height(breakPoint.getHeight())
                    .latitude(breakPoint.getLatitude())
                    .longitude(breakPoint.getLongitude())
                    .workspaceId(workspaceId)
                    .build();
            Boolean insert = flightTaskBreakpointService.insert(flightTaskBreakpoint);
        }
        if (WaylineMissionStateEnum.ARRIVE_FIRST_WAYPOINT.equals(output.getExt().getWaylineMissionState())||
                WaylineMissionStateEnum.WAYLINE_EXECUTING.equals(output.getExt().getWaylineMissionState())||
                WaylineMissionStateEnum.WAYLINE_PREPARING.equals(output.getExt().getWaylineMissionState())) {
            //获取相关的一些信息用于后面计算  比如飞行开始的总里程 时间等
            Optional<OsdDockDrone> deviceOsd = deviceRedisService.getDeviceOsd(deviceOpt.get().getChildDeviceSn(), OsdDockDrone.class);
            if (deviceOsd.isEmpty()) {
                throw new RuntimeException("无人机不在线或未起飞");
            }
            boolean insert = true;
            if(deviceOsd.get().getLatitude() == 0.0f || deviceOsd.get().getLongitude() == 0.0f){
                insert = false;
            }
            // 检查接近零值
            if (Math.abs(deviceOsd.get().getLatitude()) < MIN_VALUE_THRESHOLD || Math.abs(deviceOsd.get().getLatitude()) < MIN_VALUE_THRESHOLD) {
                insert =  false;
            }
            if(insert){
                FlightTrackDTO flightTrack = FlightTrackDTO.builder()
                        .flightId(ext.getFlightId())
                        .createTime(response.getTimestamp())
                        .height(deviceOsd.get().getHeight())
                        .latitude(deviceOsd.get().getLatitude())
                        .longitude(deviceOsd.get().getLongitude())
                        .elevation(deviceOsd.get().getElevation())
                        .build();
                String trackKey = "track_" + response.getGateway();
                boolean isAdd = RedisOpsUtils.zAdd(RedisConst.FLIGHT_TRACK_PREFIX + response.getGateway(),
                        flightTrack,
                        flightTrack.getCreateTime());
            }
        }

        FlighttaskStatusEnum statusEnum = output.getStatus();
        waylineRedisService.setRunningWaylineJob(response.getGateway(), eventsReceiver);

        if (statusEnum.isEnd()) {
            WaylineJobDTO job = WaylineJobDTO.builder()
                    .jobId(response.getBid())
                    .status(WaylineJobStatusEnum.SUCCESS.getVal())
                    .completedTime(LocalDateTime.now())
                    .mediaCount(output.getExt().getMediaCount())
                    .build();

            // record the update of the media count.
            if (Objects.nonNull(job.getMediaCount()) && job.getMediaCount() != 0) {
                mediaRedisService.setMediaCount(response.getGateway(), job.getJobId(),
                        MediaFileCountDTO.builder().deviceSn(deviceOpt.get().getChildDeviceSn())
                                .jobId(response.getBid()).mediaCount(job.getMediaCount()).uploadedCount(0).build());
            }

            if (FlighttaskStatusEnum.OK != statusEnum) {
                job.setCode(eventsReceiver.getResult().getCode());
                job.setStatus(WaylineJobStatusEnum.FAILED.getVal());
            }
            waylineJobService.updateJob(job);
            waylineRedisService.delRunningWaylineJob(response.getGateway());
            waylineRedisService.delRunningJobDTO(response.getGateway());
            waylineRedisService.delPausedWaylineJob(response.getBid());
        }

        webSocketMessageService.sendBatch(deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                BizCodeEnum.FLIGHT_TASK_PROGRESS.getCode(), eventsReceiver);

        return new TopicEventsResponse<>();
    }

    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    @Override
    public TopicRequestsResponse<MqttReply<FlighttaskResourceGetResponse>> flighttaskResourceGet(TopicRequestsRequest<FlighttaskResourceGetRequest> response, MessageHeaders headers) {
        String jobId = response.getData().getFlightId();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(response.getGateway());
        if (deviceOpt.isEmpty()) {
            log.error("The device is offline, please try again later.");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.DEVICE_OFFLINE));
        }
        Optional<WaylineJobDTO> waylineJobOpt = waylineJobService.getJobByJobId(deviceOpt.get().getWorkspaceId(), jobId);
        if (waylineJobOpt.isEmpty()) {
            log.error("The wayline job does not exist.");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }

        WaylineJobDTO waylineJob = waylineJobOpt.get();

        // get wayline file
        Optional<GetWaylineListResponse> waylineFile = waylineFileService.getWaylineByWaylineId(waylineJob.getWorkspaceId(), waylineJob.getFileId());
        if (waylineFile.isEmpty()) {
            log.error("The wayline file does not exist.");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.ILLEGAL_ARGUMENT));
        }
        // get file url
        try {
            URL url = waylineFileService.getObjectUrl(waylineJob.getWorkspaceId(), waylineFile.get().getId());
            return new TopicRequestsResponse<MqttReply<FlighttaskResourceGetResponse>>().setData(
                    MqttReply.success(new FlighttaskResourceGetResponse()
                            .setFile(new FlighttaskFile()
                                    .setUrl(url.toString())
                                    .setFingerprint(waylineFile.get().getSign()))));
        } catch (SQLException | NullPointerException e) {
            log.error("飞行资源获取失败,未找到航线信息");
            return new TopicRequestsResponse().setData(MqttReply.error(CommonErrorEnum.SYSTEM_ERROR));
        }
    }
}
