package com.dji.zc.cloud.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dji.sdk.cloudapi.device.DockModeCodeEnum;
import com.dji.sdk.cloudapi.device.DroneModeCodeEnum;
import com.dji.sdk.cloudapi.device.OsdDock;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.wayline.*;
import com.dji.sdk.common.Pagination;
import com.dji.sdk.common.PaginationData;
import com.dji.zc.cloud.common.util.UserData;
import com.dji.zc.cloud.common.util.UserDataUtils;
import com.dji.zc.cloud.component.mqtt.model.EventsReceiver;
import com.dji.zc.cloud.component.redis.RedisConst;
import com.dji.zc.cloud.component.redis.RedisOpsUtils;
import com.dji.zc.cloud.control.model.param.TakeoffToPointParam;
import com.dji.zc.cloud.manage.model.dto.DeviceDTO;
import com.dji.zc.cloud.manage.model.entity.DeviceEntity;
import com.dji.zc.cloud.manage.service.IDeviceRedisService;
import com.dji.zc.cloud.manage.service.IDeviceService;
import com.dji.zc.cloud.media.model.MediaFileCountDTO;
import com.dji.zc.cloud.media.model.MediaFileEntity;
import com.dji.zc.cloud.media.service.IFileService;
import com.dji.zc.cloud.wayline.dao.IWaylineJobMapper;
import com.dji.zc.cloud.wayline.model.dto.JobStatusCountDTO;
import com.dji.zc.cloud.wayline.model.dto.TaskTypeDTO;
import com.dji.zc.cloud.wayline.model.dto.TopRouteDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineJobDTO;
import com.dji.zc.cloud.wayline.model.dto.query.WaylineJobQuery;
import com.dji.zc.cloud.wayline.model.entity.WaylineFileEntity;
import com.dji.zc.cloud.wayline.model.entity.WaylineJobEntity;
import com.dji.zc.cloud.wayline.model.enums.WaylineJobStatusEnum;
import com.dji.zc.cloud.wayline.model.param.CreateJobParam;
import com.dji.zc.cloud.wayline.service.IWaylineFileService;
import com.dji.zc.cloud.wayline.service.IWaylineJobService;
import com.dji.zc.cloud.wayline.service.IWaylineRedisService;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author sean
 * @version 1.1
 */
@Service
@Transactional
@Slf4j
public class WaylineJobServiceImpl implements IWaylineJobService {

    @Resource
    private IWaylineJobMapper mapper;

    @Resource
    private IWaylineFileService waylineFileService;

    @Resource
    private IDeviceService deviceService;

    @Resource
    @Lazy
    private IFileService fileService;

    @Resource
    private IDeviceRedisService deviceRedisService;

    @Resource
    private IWaylineRedisService waylineRedisService;

    private Optional<WaylineJobDTO> insertWaylineJob(WaylineJobEntity jobEntity) {
        int id = mapper.insert(jobEntity);
        if (id <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.entity2Dto(jobEntity));
    }

    @Override
    public Optional<WaylineJobDTO> createWaylineJob(TakeoffToPointParam param, String workspaceId, String sn, String username) {
        if (Objects.isNull(param)) {
            return Optional.empty();
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

        long timestamp = System.currentTimeMillis();
        // Immediate tasks, allocating time on the backend.
        WaylineJobEntity jobEntity = WaylineJobEntity.builder()
                .name("一键巡航任务" + LocalDateTime.now().format(formatter))
                .dockSn(sn)
                .fileId(null)
                .username(username)
                .workspaceId(workspaceId)
                .jobId(param.getFlightId())
                .beginTime(timestamp)
                .endTime(timestamp)
                .status(WaylineJobStatusEnum.PENDING.getVal())
                .taskType(TaskTypeEnum.TAKEOFF.getType())
                .waylineType(WaylineTypeEnum.TAKE_OFF_TO_POINT.getValue())
                .outOfControlAction(0)
                .rthAltitude(param.getRthAltitude().intValue())
                .needAi(false)
                .aiModelType(null)
                .mediaCount(0)
                .build();

        return insertWaylineJob(jobEntity);
    }

    @Override
    public Optional<WaylineJobDTO> createWaylineJob(CreateJobParam param, String workspaceId, String username, Long beginTime, Long endTime) {
        if (Objects.isNull(param)) {
            return Optional.empty();
        }

        // Immediate tasks, allocating time on the backend.
        WaylineJobEntity jobEntity = WaylineJobEntity.builder()
                .name(param.getName())
                .dockSn(param.getDockSn())
                .fileId(param.getFileId())
                .username(username)
                .workspaceId(workspaceId)
                .jobId(UUID.randomUUID().toString())
                .beginTime(beginTime)
                .endTime(endTime)
                .status(WaylineJobStatusEnum.PENDING.getVal())
                .taskType(param.getTaskType().getType())
                .waylineType(param.getWaylineType().getValue())
                .outOfControlAction(param.getOutOfControlAction().getAction())
                .rthAltitude(param.getRthAltitude())
                .needAi(param.getNeedAi())
                .aiModelType(param.getAiModelTypes())
                .mediaCount(0)
                .build();

        return insertWaylineJob(jobEntity);
    }

    @Override
    public Optional<WaylineJobDTO> createWaylineJob(CreateJobParam param, String workspaceId, String username, Long beginTime, Long endTime, String batchId) {
        if (Objects.isNull(param)) {
            return Optional.empty();
        }
        // Immediate tasks, allocating time on the backend.
        WaylineJobEntity jobEntity = WaylineJobEntity.builder()
                .name(param.getName())
                .dockSn(param.getDockSn())
                .fileId(param.getFileId())
                .username(username)
                .workspaceId(workspaceId)
                .jobId(UUID.randomUUID().toString())
                .beginTime(beginTime)
                .endTime(endTime)
                .status(WaylineJobStatusEnum.PENDING.getVal())
                .taskType(param.getTaskType().getType())
                .waylineType(param.getWaylineType().getValue())
                .outOfControlAction(param.getOutOfControlAction().getAction())
                .rthAltitude(param.getRthAltitude())
                .needAi(param.getNeedAi())
                .aiModelType(param.getAiModelTypes())
                .batchId(batchId)
                .mediaCount(0)
                .build();

        return insertWaylineJob(jobEntity);
    }

    @Override
    public Optional<WaylineJobDTO> createWaylineJobByParent(String workspaceId, String parentId) {
        Optional<WaylineJobDTO> parentJobOpt = this.getJobByJobId(workspaceId, parentId);
        if (parentJobOpt.isEmpty()) {
            return Optional.empty();
        }
        WaylineJobEntity jobEntity = this.dto2Entity(parentJobOpt.get());
        jobEntity.setJobId(UUID.randomUUID().toString());
        jobEntity.setErrorCode(null);
        jobEntity.setCompletedTime(null);
        jobEntity.setExecuteTime(null);
        jobEntity.setStatus(WaylineJobStatusEnum.PENDING.getVal());
        jobEntity.setParentId(parentId);

        return this.insertWaylineJob(jobEntity);
    }

    public List<WaylineJobDTO> getJobsByConditions(String workspaceId, Collection<String> jobIds, WaylineJobStatusEnum status) {
        return mapper.selectList(
                        new LambdaQueryWrapper<WaylineJobEntity>()
                                .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                                .eq(Objects.nonNull(status), WaylineJobEntity::getStatus, status.getVal())
                                .and(!CollectionUtils.isEmpty(jobIds),
                                        wrapper -> jobIds.forEach(id -> wrapper.eq(WaylineJobEntity::getJobId, id).or())))
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<WaylineJobDTO> getJobByJobId(String workspaceId, String jobId) {
        WaylineJobEntity jobEntity = mapper.selectOne(
                new LambdaQueryWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                        .eq(WaylineJobEntity::getJobId, jobId));
        return Optional.ofNullable(entity2Dto(jobEntity));
    }

    @Override
    public Optional<WaylineJobDTO> getJobBySimpleJobId(String jobId) {
        WaylineJobEntity jobEntity = mapper.selectOne(
                new LambdaQueryWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getJobId, jobId));
        return Optional.ofNullable(entity2Dto(jobEntity));
    }

    @Override
    public Boolean updateJob(WaylineJobDTO dto) {
        return mapper.update(this.dto2Entity(dto),
                new LambdaUpdateWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getJobId, dto.getJobId())) > 0;
    }

    @Override
    public PaginationData<WaylineJobDTO> getJobsByWorkspaceId(String workspaceId, long page, long pageSize) {
        Page<WaylineJobEntity> pageData = mapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<WaylineJobEntity>()
                        .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(WaylineJobEntity::getId));
        List<WaylineJobDTO> records = pageData.getRecords()
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());

        return new PaginationData<>(records, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));
    }

    private WaylineJobEntity dto2Entity(WaylineJobDTO dto) {
        WaylineJobEntity.WaylineJobEntityBuilder builder = WaylineJobEntity.builder();
        if (dto == null) {
            return builder.build();
        }
        if (Objects.nonNull(dto.getBeginTime())) {
            builder.beginTime(dto.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (Objects.nonNull(dto.getEndTime())) {
            builder.endTime(dto.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (Objects.nonNull(dto.getExecuteTime())) {
            builder.executeTime(dto.getExecuteTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        if (Objects.nonNull(dto.getCompletedTime())) {
            builder.completedTime(dto.getCompletedTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return builder.status(dto.getStatus())
                .mediaCount(dto.getMediaCount())
                .name(dto.getJobName())
                .errorCode(dto.getCode())
                .jobId(dto.getJobId())
                .fileId(dto.getFileId())
                .dockSn(dto.getDockSn())
                .workspaceId(dto.getWorkspaceId())
                .needAi(dto.getNeedAi())
                .aiModelType(dto.getAiModelType())
                .taskType(Optional.ofNullable(dto.getTaskType()).map(TaskTypeEnum::getType).orElse(null))
                .waylineType(Optional.ofNullable(dto.getWaylineType()).map(WaylineTypeEnum::getValue).orElse(null))
                .username(dto.getUsername())
                .rthAltitude(dto.getRthAltitude())
                .outOfControlAction(Optional.ofNullable(dto.getOutOfControlAction())
                        .map(OutOfControlActionEnum::getAction).orElse(null))
                .parentId(dto.getParentId())
                .hasWarning(dto.getHasWarning())
                .build();
    }

    public WaylineJobStatusEnum getWaylineState(String dockSn) {
        Optional<DeviceDTO> dockOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (dockOpt.isEmpty() || !StringUtils.hasText(dockOpt.get().getChildDeviceSn())) {
            return WaylineJobStatusEnum.UNKNOWN;
        }
        Optional<OsdDock> dockOsdOpt = deviceRedisService.getDeviceOsd(dockSn, OsdDock.class);
        Optional<OsdDockDrone> deviceOsdOpt = deviceRedisService.getDeviceOsd(dockOpt.get().getChildDeviceSn(), OsdDockDrone.class);
        if (dockOsdOpt.isEmpty() || deviceOsdOpt.isEmpty() || DockModeCodeEnum.WORKING != dockOsdOpt.get().getModeCode()) {
            return WaylineJobStatusEnum.UNKNOWN;
        }

        OsdDockDrone osdDevice = deviceOsdOpt.get();
        if (DroneModeCodeEnum.WAYLINE == osdDevice.getModeCode()
                || DroneModeCodeEnum.MANUAL == osdDevice.getModeCode()
                || DroneModeCodeEnum.TAKEOFF_AUTO == osdDevice.getModeCode()) {
            if (StringUtils.hasText(waylineRedisService.getPausedWaylineJobId(dockSn))) {
                return WaylineJobStatusEnum.PAUSED;
            }
            if (waylineRedisService.getRunningWaylineJob(dockSn).isPresent()) {
                return WaylineJobStatusEnum.IN_PROGRESS;
            }
        }
        return WaylineJobStatusEnum.UNKNOWN;
    }

    private WaylineJobDTO entity2Dto(WaylineJobEntity entity) {
        if (entity == null) {
            return null;
        }
        WaylineJobDTO.WaylineJobDTOBuilder builder = WaylineJobDTO.builder()
                .jobId(entity.getJobId())
                .batchId(entity.getBatchId())
                .jobName(entity.getName())
                .fileId(entity.getFileId())
                .fileName(waylineFileService.getWaylineByWaylineId(entity.getWorkspaceId(), entity.getFileId())
                        .orElse(new GetWaylineListResponse()).getName())
                .dockSn(entity.getDockSn())
                .dockName(deviceService.getDeviceBySn(entity.getDockSn())
                        .orElse(DeviceDTO.builder().build()).getNickname())
                .username(entity.getUsername())
                .workspaceId(entity.getWorkspaceId())
                .status(WaylineJobStatusEnum.IN_PROGRESS.getVal() == entity.getStatus() &&
                        entity.getJobId().equals(waylineRedisService.getPausedWaylineJobId(entity.getDockSn())) ?
                        WaylineJobStatusEnum.PAUSED.getVal() : entity.getStatus())
                .code(entity.getErrorCode())
                .beginTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getBeginTime()), ZoneId.systemDefault()))
                .endTime(Objects.nonNull(entity.getEndTime()) ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getEndTime()), ZoneId.systemDefault()) : null)
                .executeTime(Objects.nonNull(entity.getExecuteTime()) ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getExecuteTime()), ZoneId.systemDefault()) : null)
                .completedTime(WaylineJobStatusEnum.find(entity.getStatus()).getEnd() ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getUpdateTime()), ZoneId.systemDefault()) : null)
                .taskType(TaskTypeEnum.find(entity.getTaskType()))
                .waylineType(WaylineTypeEnum.find(entity.getWaylineType()))
                .rthAltitude(entity.getRthAltitude())
                .outOfControlAction(OutOfControlActionEnum.find(entity.getOutOfControlAction()))
                .hasWarning(entity.getHasWarning())
                .needAi(entity.getNeedAi())
                .aiModelType(entity.getAiModelType())
                .mediaCount(entity.getMediaCount());

        if (StringUtils.hasText(entity.getAiModelType())) {
            builder.modelCount(entity.getAiModelType().split(",").length);
        }
        if (Objects.nonNull(entity.getEndTime())) {
            builder.endTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getEndTime()), ZoneId.systemDefault()));
        }
        if (Objects.nonNull(entity.getErrorCode())) {
            builder.reason(WaylineErrorCodeEnum.find(entity.getErrorCode()).getMessage());
        }

        if (WaylineJobStatusEnum.IN_PROGRESS.getVal() == entity.getStatus()) {
            builder.progress(waylineRedisService.getRunningWaylineJob(entity.getDockSn())
                    .map(EventsReceiver::getOutput)
                    .map(FlighttaskProgress::getProgress)
                    .map(FlighttaskProgressData::getPercent)
                    .orElse(null));
        }

        if (entity.getMediaCount() == 0) {
            return builder.build();
        }

        // sync the number of media files
        String key = RedisConst.MEDIA_HIGHEST_PRIORITY_PREFIX + entity.getDockSn();
        String countKey = RedisConst.MEDIA_FILE_PREFIX + entity.getDockSn();
        Object mediaFileCount = RedisOpsUtils.hashGet(countKey, entity.getJobId());
        if (Objects.nonNull(mediaFileCount)) {
            builder.uploadedCount(((MediaFileCountDTO) mediaFileCount).getUploadedCount())
                    .uploading(RedisOpsUtils.checkExist(key) && entity.getJobId().equals(((MediaFileCountDTO) RedisOpsUtils.get(key)).getJobId()));
            return builder.build();
        }

        int uploadedSize = fileService.getFilesByWorkspaceAndJobId(entity.getWorkspaceId(), entity.getJobId()).size();
        // All media for this job have been uploaded.
        if (uploadedSize >= entity.getMediaCount()) {
            return builder.uploadedCount(uploadedSize).build();
        }
        RedisOpsUtils.hashSet(countKey, entity.getJobId(),
                MediaFileCountDTO.builder()
                        .jobId(entity.getJobId())
                        .mediaCount(entity.getMediaCount())
                        .uploadedCount(uploadedSize).build());
        return builder.build();
    }

    @Override
    public PaginationData<WaylineJobDTO> getJobsFilter(String workspaceId, String dockSn, WaylineJobQuery query, long page, long pageSize) {
        Optional<DeviceDTO> deviceOnline = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOnline.isEmpty()) {
            throw new RuntimeException("机场掉线，请重新启动机场后重试");
        }
//        CharSequence field = FieldUtil.verifyAndSetColumn(query.getOrderBy(), WaylineJobEntity.class);
//        if (field != null) {
//            query.setOrderBy((String) field);
//        }
        Page<WaylineJobEntity> pageData = mapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<WaylineJobEntity>()
                        .like(!StringUtil.isNullOrEmpty(query.getWaylineJobName()), WaylineJobEntity::getName, query.getWaylineJobName())
                        .eq(query.getWaylineType() != null, WaylineJobEntity::getWaylineType, query.getWaylineType())
                        .ge(query.getStartTime() != null, WaylineJobEntity::getCreateTime, query.getStartTime())
                        .le(query.getEndTime() != null, WaylineJobEntity::getCreateTime, query.getEndTime())
                        .in(!CollectionUtils.isEmpty(query.getStatus()), WaylineJobEntity::getStatus, query.getStatus())
                        .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                        .eq(WaylineJobEntity::getDockSn, dockSn)
                        .last("order by " + query.getOrderBy() + " " + query.getSortBy()));

        List<WaylineJobDTO> records = pageData.getRecords()
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());

        records.forEach(item -> {
            item.setDeviceName(deviceOnline.get().getChildren().getDeviceName());
            item.setTaskName(item.getTaskType() != TaskTypeEnum.IMMEDIATE ? "自动飞行" : "手动飞行");
        });


        return new PaginationData<>(records, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));
    }

    @Override
    public PaginationData<WaylineJobDTO> getJobDicFilter(String workspaceId, String dockSn, WaylineJobQuery query, long page, long pageSize) {
        MPJLambdaWrapper<WaylineJobEntity> queryWrapper = new MPJLambdaWrapper<>();
        queryWrapper.selectAll(WaylineJobEntity.class)
                .innerJoin(MediaFileEntity.class, MediaFileEntity::getJobId, WaylineJobEntity::getJobId)
                .like(!StringUtil.isNullOrEmpty(query.getWaylineJobName()), WaylineJobEntity::getName, query.getWaylineJobName())
                .eq(query.getWaylineType() != null, WaylineJobEntity::getWaylineType, query.getWaylineType())
                .ge(query.getStartTime() != null, WaylineJobEntity::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, WaylineJobEntity::getCreateTime, query.getEndTime())
                .in(!CollectionUtils.isEmpty(query.getStatus()), WaylineJobEntity::getStatus, query.getStatus())
                .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                .eq(WaylineJobEntity::getDockSn, dockSn)
                .groupBy(WaylineJobEntity::getJobId) // 按 jobId 去重
                .orderByDesc(WaylineJobEntity::getCreateTime);

        Page<WaylineJobEntity> pageData = mapper.selectJoinPage(
                new Page<>(page, pageSize),
                WaylineJobEntity.class, queryWrapper);
        Optional<DeviceDTO> deviceOnline = deviceRedisService.getDeviceOnline(dockSn);
        List<WaylineJobDTO> records = pageData.getRecords()
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());

        records.forEach(item -> {
            item.setDeviceName(deviceOnline.get().getChildren().getDeviceName());
            item.setTaskName(item.getTaskType() != TaskTypeEnum.IMMEDIATE ? "自动飞行" : "手动飞行");
        });


        return new PaginationData<>(records, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));
    }

    @Override
    public PaginationData<WaylineJobDTO> getPendingWaylines(String workspaceId, String dockSn, Long page, Long pageSize) {
        var query = new LambdaQueryWrapper<WaylineJobEntity>();
        query.eq(WaylineJobEntity::getDockSn, dockSn);
        query.eq(WaylineJobEntity::getWorkspaceId, workspaceId);
        query.in(WaylineJobEntity::getStatus, List.of(1));
        query.orderByAsc(WaylineJobEntity::getCreateTime);
        Page<WaylineJobEntity> waylineJobEntityPage = mapper.selectPage(new Page<>(page, pageSize), query);
        List<WaylineJobDTO> pageRecords = waylineJobEntityPage.getRecords().stream().map(this::entity2Dto).collect(Collectors.toList());
        return new PaginationData<>(pageRecords, new Pagination(waylineJobEntityPage.getCurrent(), waylineJobEntityPage.getSize(), waylineJobEntityPage.getTotal()));
    }

    @Override
    public Integer getJobCountByBatchId(String batchId) {
        var query = new LambdaQueryWrapper<WaylineJobEntity>();
        query.eq(WaylineJobEntity::getBatchId, batchId);
        return mapper.selectCount(query);
    }

    @Override
    public List<WaylineJobEntity> getJobsByBatchId(String batchId) {
        var query = new LambdaQueryWrapper<WaylineJobEntity>();
        query.eq(WaylineJobEntity::getBatchId, batchId);
        return mapper.selectList(query);
    }

    public String validateJobHasMediaZip(String jobId) {
        WaylineJobEntity waylineJobEntity = mapper.selectOne(new LambdaQueryWrapper<WaylineJobEntity>().eq(WaylineJobEntity::getJobId, jobId));
        if (waylineJobEntity == null) {
            return null;
        }
        return waylineJobEntity.getMediaUrl();
    }

    public void putMediaUrlToJob(String jobId, String mediaUrl) {
        WaylineJobEntity waylineJobEntity = mapper.selectOne(new LambdaQueryWrapper<WaylineJobEntity>().eq(WaylineJobEntity::getJobId, jobId));
        if (waylineJobEntity == null) {
            return;
        }
        waylineJobEntity.setMediaUrl(mediaUrl);
        mapper.updateById(waylineJobEntity);
    }

    @Override
    public PaginationData<WaylineJobDTO> getWarningJobsByWorkspaceId(String workspaceId, String dockSn, String modelTypes, Long page, Long pageSize) {
        var query = new LambdaQueryWrapper<WaylineJobEntity>()
                .eq(WaylineJobEntity::getWorkspaceId, workspaceId)
                .eq(StringUtils.hasText(dockSn), WaylineJobEntity::getDockSn, dockSn)
                .eq(WaylineJobEntity::getNeedAi, true);
        if (StringUtils.hasText(modelTypes)) {
            var typeList = modelTypes.split(",");
            query.and(wrapper -> {
                for (int i = 0; i < typeList.length; i++) {
                    if (i == 0) {
                        wrapper.like(WaylineJobEntity::getAiModelType, typeList[i]);
                    } else {
                        wrapper.or().like(WaylineJobEntity::getAiModelType, typeList[i]);
                    }
                }
            });
        }
        query.orderByDesc(WaylineJobEntity::getHasWarning);
        query.orderByDesc(WaylineJobEntity::getId);

        Page<WaylineJobEntity> pageData = mapper.selectPage(
                new Page<>(page, pageSize), query);
        List<WaylineJobDTO> records = pageData.getRecords()
                .stream()
                .map(this::entity2Dto)
                .collect(Collectors.toList());

        return new PaginationData<>(records, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));
    }

    @Override
    public WaylineJobEntity getById(String jobId) {
        return mapper.selectOne(new LambdaQueryWrapper<WaylineJobEntity>().eq(WaylineJobEntity::getJobId, jobId));
    }

    @Override
    public PaginationData<WaylineJobDTO> getJobsPageList(WaylineJobQuery query, Long page, Long pageSize) {
        MPJLambdaWrapper<WaylineJobEntity> lambdaquery = new MPJLambdaWrapper<>();
        lambdaquery.selectAll(WaylineJobEntity.class)
                .leftJoin(DeviceEntity.class, DeviceEntity::getDeviceSn, WaylineJobEntity::getDockSn);
        lambdaquery.like(org.apache.commons.lang3.StringUtils.isNotBlank(query.getWaylineJobName()), WaylineJobEntity::getName, query.getWaylineJobName());
        if (query.getTaskType() != null) {
            lambdaquery.eq(true, WaylineJobEntity::getTaskType, query.getTaskType().getType());
        }
        lambdaquery.like(org.apache.commons.lang3.StringUtils.isNotBlank(query.getPlaneName()), DeviceEntity::getNickname, query.getPlaneName());
        lambdaquery.ge(query.getStartTime() != null, WaylineJobEntity::getExecuteTime, query.getStartTime());
        lambdaquery.le(query.getEndTime() != null, WaylineJobEntity::getExecuteTime, query.getEndTime());
        if (!CollectionUtils.isEmpty(query.getWorkSpaceIds())) {
            lambdaquery.in(WaylineJobEntity::getWorkspaceId, query.getWorkSpaceIds());
        } else {
            UserData userData = UserDataUtils.getUserData();
            if (CollectionUtils.isEmpty(userData.getDeviceList())) {
                lambdaquery.in(WaylineJobEntity::getWorkspaceId, "000");
            } else {
                var workspaceIds = userData.getDeviceList().stream().map(UserData.DeviceInfoDTO::getWorkspaceId).collect(Collectors.toList());
                lambdaquery.in(WaylineJobEntity::getWorkspaceId, workspaceIds);
            }
        }
        lambdaquery.in(!CollectionUtils.isEmpty(query.getStatus()), WaylineJobEntity::getStatus, query.getStatus());
        lambdaquery.last("order by " + query.getOrderBy() + " " + query.getSortBy());
        Page<WaylineJobEntity> pageData = mapper.selectJoinPage(
                new Page<>(page, pageSize),
                WaylineJobEntity.class, lambdaquery);
        List<WaylineJobDTO> pageRecords = pageData.getRecords().stream().map(this::waylineJobEntityDTO).collect(Collectors.toList());
        List<String> sns = pageRecords.stream().map(WaylineJobDTO::getDockSn).collect(Collectors.toList());
        Map<String, String> nameMap = null;
        if (!CollectionUtils.isEmpty(sns)) {
            List<DeviceEntity> deviceList = deviceService.getDeviceBySns(sns);
            if (!CollectionUtils.isEmpty(deviceList)) {
                // 创建Map，key为id
                nameMap = deviceList.stream()
                        .collect(Collectors.toMap(DeviceEntity::getDeviceSn, DeviceEntity::getNickname));
            }

        }
        Map<String, String> finalNameMap = nameMap;
        pageRecords.forEach(item -> {
            if (finalNameMap != null && org.apache.commons.lang3.StringUtils.isNotEmpty(item.getDockSn())) {
                if (finalNameMap.get(item.getDockSn()) != null) {
                    item.setDockName(finalNameMap.get(item.getDockSn()));
                } else {
                    item.setDockName(null);
                }
            }
            item.setTime(calculateFlightDuration(item.getExecuteTime(), item.getCompletedTime()));
        });
        return new PaginationData<>(pageRecords, new Pagination(pageData.getCurrent(), pageData.getSize(), pageData.getTotal()));

    }

    /**
     * 简易实体转换dto
     */
    private WaylineJobDTO waylineJobEntityDTO(WaylineJobEntity entity) {
        if (entity == null) {
            return null;
        }
        WaylineJobDTO.WaylineJobDTOBuilder builder = WaylineJobDTO.builder()
                .jobId(entity.getJobId())
                .batchId(entity.getBatchId())
                .jobName(entity.getName())
                .fileId(entity.getFileId())
                .dockSn(entity.getDockSn())
//                .dockName(deviceService.getDeviceBySn(entity.getDockSn())
//                        .orElse(DeviceDTO.builder().build()).getNickname())
                .username(entity.getUsername())
                .workspaceId(entity.getWorkspaceId())
                .status(WaylineJobStatusEnum.IN_PROGRESS.getVal() == entity.getStatus() &&
                        entity.getJobId().equals(waylineRedisService.getPausedWaylineJobId(entity.getDockSn())) ?
                        WaylineJobStatusEnum.PAUSED.getVal() : entity.getStatus())
                .code(entity.getErrorCode())
                .beginTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getBeginTime()), ZoneId.systemDefault()))
                .endTime(Objects.nonNull(entity.getEndTime()) ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getEndTime()), ZoneId.systemDefault()) : null)
                .executeTime(Objects.nonNull(entity.getExecuteTime()) ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getExecuteTime()), ZoneId.systemDefault()) : null)
                .completedTime(WaylineJobStatusEnum.find(entity.getStatus()).getEnd() ?
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(entity.getUpdateTime()), ZoneId.systemDefault()) : null)
                .taskType(TaskTypeEnum.find(entity.getTaskType()))
                .waylineType(WaylineTypeEnum.find(entity.getWaylineType()))
                .rthAltitude(entity.getRthAltitude())
                .outOfControlAction(OutOfControlActionEnum.find(entity.getOutOfControlAction()))
                .hasWarning(entity.getHasWarning())
                .needAi(entity.getNeedAi())
                .aiModelType(entity.getAiModelType())
                .mediaCount(entity.getMediaCount());

        if (StringUtils.hasText(entity.getAiModelType())) {
            builder.modelCount(entity.getAiModelType().split(",").length);
        }
        if (Objects.nonNull(entity.getErrorCode())) {
            builder.reason(WaylineErrorCodeEnum.find(entity.getErrorCode()).getMessage());
        }
        return builder.build();
    }


    @Override
    public WaylineJobDTO getJobInfo(String jobId) {
        var lambdaquery = new LambdaQueryWrapper<WaylineJobEntity>();
        lambdaquery.eq(WaylineJobEntity::getJobId, jobId);
        WaylineJobEntity waylineJob = mapper.selectOne(lambdaquery);
        WaylineJobDTO dto = entity2Dto(waylineJob);
        if (waylineJob != null) {
            dto.setTime(calculateFlightDuration(dto.getExecuteTime(), dto.getCompletedTime()));
        }
        return dto;
    }

    @Override
    public TaskTypeDTO getTaskTypeCount() {
        QueryWrapper<WaylineJobEntity> queryWrapper = new QueryWrapper<>();
        UserData userData = UserDataUtils.getUserData();
        queryWrapper.select("task_type AS taskType, COUNT(*) AS count");
        if (CollectionUtils.isEmpty(userData.getDeviceList())) {
            queryWrapper.lambda().in(WaylineJobEntity::getWorkspaceId, "000");
        } else {
            var workspaceIds = userData.getDeviceList().stream()
                    .map(UserData.DeviceInfoDTO::getWorkspaceId)
                    .collect(Collectors.toList());
            queryWrapper.lambda().in(WaylineJobEntity::getWorkspaceId, workspaceIds);
        }

        queryWrapper.lambda().groupBy(WaylineJobEntity::getTaskType);

        List<Map<String, Object>> results = mapper.selectMaps(queryWrapper);

        // 初始化 TaskStatusDTO 字段
        int immediateNum = 0;    // taskType 0: 立即执行
        int scheduledNum = 0;    // taskType 1: 定时执行
        int conditionalNum = 0;  // taskType 2: 条件执行
        int oneClickReturnNum = 0; // taskType 3: 一键返航
        // 将分组统计结果赋值
        for (Map<String, Object> result : results) {
            Integer taskType = (Integer) result.get("taskType");
            Long count = (Long) result.get("count");
            switch (taskType) {
                case 0:
                    immediateNum = count.intValue();
                    break;
                case 1:
                    scheduledNum = count.intValue();
                    break;
                case 2:
                    conditionalNum = count.intValue();
                    break;
                case 3:
                    oneClickReturnNum = count.intValue();
                    break;
                default:
                    break;
            }
        }
        return new TaskTypeDTO(immediateNum, scheduledNum, conditionalNum, oneClickReturnNum);
    }

    @Override
    public List<TopRouteDTO> getTop5Count() {
        MPJLambdaWrapper<WaylineJobEntity> wrapper = new MPJLambdaWrapper<>();
        UserData userData = UserDataUtils.getUserData();
        wrapper.select("t1.name AS waylineName",
                "t.file_id AS fileId",
                "COUNT(t.id) AS taskCount",
                "SUM(t.media_count) AS totalMediaCount");
        wrapper.leftJoin(WaylineFileEntity.class, WaylineFileEntity::getWaylineId, WaylineJobEntity::getFileId);
        if (CollectionUtils.isEmpty(userData.getDeviceList())) {
            wrapper.in(WaylineJobEntity::getWorkspaceId, "000");
        } else {
            List<String> workspaceIds = userData.getDeviceList().stream()
                    .map(UserData.DeviceInfoDTO::getWorkspaceId)
                    .collect(Collectors.toList());
            wrapper.in(WaylineJobEntity::getWorkspaceId, workspaceIds);
        }
        wrapper.isNotNull(WaylineFileEntity::getName);
        wrapper.groupBy("t.file_id", "t1.name");
        wrapper.orderByDesc("taskCount");
        wrapper.last("LIMIT 5");
        List<Map<String, Object>> resultMaps = mapper.selectJoinMaps(wrapper);
        return resultMaps.stream().map(m -> new TopRouteDTO(
                (String) m.get("waylineName"),
                ((Number) m.get("taskCount")).intValue(),
                ((Number) m.get("totalMediaCount")).intValue()
        )).collect(Collectors.toList());
    }

    @Override
    public JobStatusCountDTO getStatusCount() {
        QueryWrapper<WaylineJobEntity> queryWrapper = new QueryWrapper<>();
        UserData userData = UserDataUtils.getUserData();
        queryWrapper.select("status AS status, COUNT(*) AS count");
        if (CollectionUtils.isEmpty(userData.getDeviceList())) {
            queryWrapper.lambda().in(WaylineJobEntity::getWorkspaceId, "000");
        } else {
            var workspaceIds = userData.getDeviceList().stream()
                    .map(UserData.DeviceInfoDTO::getWorkspaceId)
                    .collect(Collectors.toList());
            queryWrapper.lambda().in(WaylineJobEntity::getWorkspaceId, workspaceIds);
        }
        queryWrapper.lambda().groupBy(WaylineJobEntity::getStatus);

        // 查询各状态任务数量
        List<Map<String, Object>> statusCounts = mapper.selectMaps(queryWrapper);

        // 初始化为 0
        JobStatusCountDTO dto = new JobStatusCountDTO(null, 0, 0, 0, 0, 0);

        // 遍历聚合结果并赋值
        for (Map<String, Object> row : statusCounts) {
            Integer status = (Integer) row.get("status");
            Integer count = ((Number) row.get("count")).intValue();

            switch (status) {
                case 3: // 已执行
                    dto.setExecuted(count);
                    break;
                case 2: // 执行中
                    dto.setExecuting(count);
                    break;
                case 1: // 待执行
                    dto.setPending(count);
                    break;
                case 5: // 任务失败
                    dto.setFailed(count);
                    break;
                case 4: // 已取消
                    dto.setCancelled(count);
                    break;
                default:
                    // 未知状态（可选）
                    break;
            }
        }
        return dto;
    }

    @Override
    public List<JobStatusCountDTO> getStatusCountByMonth(String yearMonth) {
        QueryWrapper<WaylineJobEntity> queryWrapper = new QueryWrapper<>();

        UserData userData = UserDataUtils.getUserData();

        // 选择日期（按天）和状态、数量
        // 注意 create_time 是毫秒级时间戳
        queryWrapper.select("DATE_FORMAT(FROM_UNIXTIME(begin_time / 1000), '%Y-%m-%d') AS day", "status", "COUNT(*) AS count");

        // 判断工作空间权限
        if (CollectionUtils.isEmpty(userData.getDeviceList())) {
            queryWrapper.lambda().in(WaylineJobEntity::getWorkspaceId, "000");
        } else {
            var workspaceIds = userData.getDeviceList().stream()
                    .map(UserData.DeviceInfoDTO::getWorkspaceId)
                    .collect(Collectors.toList());
            queryWrapper.lambda().in(WaylineJobEntity::getWorkspaceId, workspaceIds);
        }
        queryWrapper.apply("DATE_FORMAT( FROM_UNIXTIME( begin_time / 1000 ), '%Y-%m-%d' ) like {0} ", yearMonth + "%");
        // 按日期和状态分组
        queryWrapper.groupBy("day", "status");

        List<Map<String, Object>> results = mapper.selectMaps(queryWrapper);

        // 用 Map<String, JobStatusCountDTO> 聚合
        Map<String, JobStatusCountDTO> dailyMap = new HashMap<>();

        for (Map<String, Object> row : results) {
            String day = (String) row.get("day");
            int status = ((Number) row.get("status")).intValue();
            Integer count = ((Number) row.get("count")).intValue();

            JobStatusCountDTO dto = dailyMap.getOrDefault(day, new JobStatusCountDTO(day, 0, 0, 0, 0, 0));

            switch (status) {
                case 3: // 已执行
                    dto.setExecuted(count);
                    break;
                case 2: // 执行中
                    dto.setExecuting(count);
                    break;
                case 1: // 待执行
                    dto.setPending(count);
                    break;
                case 5: // 任务失败
                    dto.setFailed(count);
                    break;
                case 4: // 已取消
                    dto.setCancelled(count);
                    break;
                default:
                    // 未知状态（可选）
                    break;
            }

            dailyMap.put(day, dto);
        }

        // 返回按日期升序排序的列表
        List<JobStatusCountDTO> list = new ArrayList<>(dailyMap.values());
        list.sort(Comparator.comparing(JobStatusCountDTO::getDay));
        return list;
    }

    public Double calculateFlightDuration(LocalDateTime beginTime, LocalDateTime endTime) {
        if (beginTime == null || endTime == null) {
            return null;
        }
        // Convert to millisecond timestamps
        long beginMillis = beginTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMillis = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // Calculate difference (milliseconds) and convert to minutes
        double minutes = (endMillis - beginMillis) / (1000.0 * 60);
        // Round to two decimal places
        return Math.round(minutes * 100.0) / 100.0;
    }
}
