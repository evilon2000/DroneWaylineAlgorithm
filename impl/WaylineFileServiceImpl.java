package com.dji.zc.cloud.wayline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dji.sdk.cloudapi.device.DeviceDomainEnum;
import com.dji.sdk.cloudapi.device.DeviceEnum;
import com.dji.sdk.cloudapi.device.DeviceSubTypeEnum;
import com.dji.sdk.cloudapi.device.DeviceTypeEnum;
import com.dji.sdk.cloudapi.wayline.GetWaylineListRequest;
import com.dji.sdk.cloudapi.wayline.GetWaylineListResponse;
import com.dji.sdk.cloudapi.wayline.WaylineTypeEnum;
import com.dji.sdk.common.Pagination;
import com.dji.sdk.common.PaginationData;
import com.dji.zc.cloud.common.constants.DroneTypeConstants;
import com.dji.zc.cloud.common.util.UserData;
import com.dji.zc.cloud.common.util.UserDataUtils;
import com.dji.zc.cloud.component.oss.model.OssConfiguration;
import com.dji.zc.cloud.component.oss.service.impl.OssServiceContext;
import com.dji.zc.cloud.manage.model.dto.DeviceDTO;
import com.dji.zc.cloud.manage.model.entity.DeviceEntity;
import com.dji.zc.cloud.manage.service.IDeviceService;
import com.dji.zc.cloud.wayline.dao.IWaylineFileMapper;
import com.dji.zc.cloud.wayline.model.dto.KmzFileProperties;
import com.dji.zc.cloud.wayline.model.dto.UpdateWaylineDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineFileDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineFileListDTO;
import com.dji.zc.cloud.wayline.model.entity.WaylineFileEntity;
import com.dji.zc.cloud.wayline.model.entity.WaylineJobEntity;
import com.dji.zc.cloud.wayline.model.param.Map2DWaylineParm;
import com.dji.zc.cloud.wayline.model.param.Map3DWaylineParam;
import com.dji.zc.cloud.wayline.model.param.WaylineQueryParam;
import com.dji.zc.cloud.wayline.service.IWaylineFileService;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.dji.zc.cloud.wayline.model.dto.KmzFileProperties.WAYLINE_FILE_SUFFIX;

/**
 * @author sean
 * @version 0.3
 * @date 2021/12/22
 */
@Service
@Transactional
public class WaylineFileServiceImpl implements IWaylineFileService {

    @Resource
    private IWaylineFileMapper mapper;

    @Resource
    private OssServiceContext ossService;
    @Resource
    private WaylineRedisServiceImpl waylineRedisServiceImpl;

    @Resource
    private IDeviceService deviceService;

    //大疆原页面调用
    @Override
    public PaginationData<GetWaylineListResponse> getWaylinesByParam(String workspaceId, String dockSn, GetWaylineListRequest param) {
        // Paging Query
        Page<WaylineFileEntity> page = mapper.selectPage(
                new Page<WaylineFileEntity>(param.getPage(), param.getPageSize()),
                new LambdaQueryWrapper<WaylineFileEntity>()
                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                        .eq(dockSn != null, WaylineFileEntity::getDockSn, dockSn)
                        .eq(Objects.nonNull(param.getFavorited()), WaylineFileEntity::getFavorited, param.getFavorited())
                        .and(param.getTemplateType() != null, wrapper -> {
                            for (WaylineTypeEnum type : param.getTemplateType()) {
                                wrapper.like(WaylineFileEntity::getTemplateTypes, type.getValue()).or();
                            }
                        })
                        .like(Objects.nonNull(param.getKey()), WaylineFileEntity::getName, param.getKey())
                        // There is a risk of SQL injection
                        .last(Objects.nonNull(param.getOrderBy()), " order by " + param.getOrderBy().toString()));

        // Wrap the results of a paging query into a custom paging object.
        List<GetWaylineListResponse> records = page.getRecords()
                .stream()
                .map(this::entityConvertToDTO)
                .collect(Collectors.toList());

        return new PaginationData<>(records, new Pagination(page.getCurrent(), page.getSize(), page.getTotal()));
    }

    /**
     * 获取航线列表
     * @param workspaceId
     * @param dockSn
     * @param param
     * @return
     */
    @Override
    public PaginationData<GetWaylineListResponse> getWaylinesByParam(String workspaceId, String dockSn, WaylineQueryParam param) {
        Page<WaylineFileEntity> page = mapper.selectPage(
                new Page<WaylineFileEntity>(param.getPage(), param.getPageSize()),
                new LambdaQueryWrapper<WaylineFileEntity>()
                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                        .eq(dockSn != null, WaylineFileEntity::getDockSn, dockSn)
                        .eq(WaylineFileEntity::getIsDelete,false)
                        .eq(WaylineFileEntity::getIsSave, 1)
                        .like(StringUtils.hasText(param.getKey()), WaylineFileEntity::getName, param.getKey())
                        .eq(param.getWaylineType() != null, WaylineFileEntity::getTemplateTypes, param.getWaylineType())
                        .last(Objects.nonNull(param.getOrderBy()), " order by " + param.getOrderBy() + " " +
                                (param.getSortBy() ? "desc" : "asc")));

        List<GetWaylineListResponse> records = page.getRecords()
                .stream()
                .map(this::entityConvertToDTO)
                .collect(Collectors.toList());

        return new PaginationData<>(records, new Pagination(page.getCurrent(), page.getSize(), page.getTotal()));

    }

    @Override
    public PaginationData<WaylineFileListDTO> getWaylinePageList(WaylineQueryParam param) {
        MPJLambdaWrapper<WaylineFileEntity> lambdaquery = new MPJLambdaWrapper<>();
        lambdaquery.selectAll(WaylineFileEntity.class)
                .leftJoin(DeviceEntity.class, DeviceEntity::getDeviceSn, WaylineJobEntity::getDockSn);
        lambdaquery.like(StringUtils.hasText(param.getKey()), WaylineFileEntity::getName, param.getKey());
        lambdaquery.eq(WaylineFileEntity::getIsDelete, 0);
        lambdaquery.eq(WaylineFileEntity::getIsSave, 1);
        lambdaquery.eq(param.getWaylineType() != null, WaylineFileEntity::getTemplateTypes, param.getWaylineType());
        lambdaquery.like(org.apache.commons.lang3.StringUtils.isNotBlank(param.getDockName()), DeviceEntity::getNickname, param.getDockName());
        UserData userData = UserDataUtils.getUserData();
        if (CollectionUtils.isEmpty(userData.getDeviceList())) {
            lambdaquery.in(WaylineJobEntity::getWorkspaceId, "000");
        } else {
            var workspaceIds = userData.getDeviceList().stream().map(UserData.DeviceInfoDTO::getWorkspaceId).collect(Collectors.toList());
            lambdaquery.in(WaylineJobEntity::getWorkspaceId, workspaceIds);
        }lambdaquery.last(Objects.nonNull(param.getOrderBy()), " order by " + param.getOrderBy() + " " +
                (param.getSortBy() ? "desc" : "asc"));
        Page<WaylineFileEntity> page = mapper.selectPage(
                new Page<WaylineFileEntity>(param.getPage(), param.getPageSize()),lambdaquery
                );
        List<WaylineFileListDTO> records = page.getRecords()
                .stream()
                .map(this::entityConvertToListDTO)
                .collect(Collectors.toList());
        List<String> sns = records.stream().map(WaylineFileListDTO::getDockSn).collect(Collectors.toList());
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
        records.forEach(item -> {
            if (finalNameMap != null && org.apache.commons.lang3.StringUtils.isNotEmpty(item.getDockSn())) {
                if (finalNameMap.get(item.getDockSn()) != null) {
                    item.setDockName(finalNameMap.get(item.getDockSn()));
                } else {
                    item.setDockName(null);
                }
            }
        });
        return new PaginationData<>(records, new Pagination(page.getCurrent(), page.getSize(), page.getTotal()));
    }

    @Override
    public Optional<GetWaylineListResponse> getWaylineByWaylineId(String workspaceId, String waylineId) {
        return Optional.ofNullable(
                this.entityConvertToDTO(
                        mapper.selectOne(
                                new LambdaQueryWrapper<WaylineFileEntity>()
                                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                                        .eq(WaylineFileEntity::getWaylineId, waylineId))));
    }
    private WaylineFileEntity getWaylineEntityByWaylineId(String workspaceId, String waylineId) {
        return mapper.selectOne(
                new LambdaQueryWrapper<WaylineFileEntity>()
                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                        .eq(WaylineFileEntity::getWaylineId, waylineId));
    }

    @Override
    public URL getObjectUrl(String workspaceId, String waylineId) throws SQLException {
        Optional<GetWaylineListResponse> waylineOpt = this.getWaylineByWaylineId(workspaceId, waylineId);
        if (waylineOpt.isEmpty()) {
            throw new SQLException(waylineId + " does not exist.");
        }
        return ossService.getObjectUrl(OssConfiguration.bucket, waylineOpt.get().getObjectKey());
    }

    @Override
    public InputStream getObjectFile(String workspaceId, String waylineId) throws SQLException {
        Optional<GetWaylineListResponse> waylineOpt = this.getWaylineByWaylineId(workspaceId, waylineId);
        if (waylineOpt.isEmpty()) {
            throw new SQLException(waylineId + " does not exist.");
        }
        return ossService.getObject(OssConfiguration.bucket, waylineOpt.get().getObjectKey());
    }

    @Override
    public Integer saveWaylineFile(String workspaceId, WaylineFileDTO metadata) {
        WaylineFileEntity file = this.dtoConvertToEntity(metadata);
        file.setWaylineId(UUID.randomUUID().toString());
        file.setWorkspaceId(workspaceId);

        if (!StringUtils.hasText(file.getSign())) {
            try (InputStream object = ossService.getObject(OssConfiguration.bucket, metadata.getObjectKey())) {
                if (object.available() == 0) {
                    throw new RuntimeException("The file " + metadata.getObjectKey() +
                            " does not exist in the bucket[" + OssConfiguration.bucket + "].");
                }
                file.setSign(DigestUtils.md5DigestAsHex(object));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        int insertId = mapper.insert(file);
        return insertId > 0 ? file.getId() : insertId;
    }

    public String saveWaylineFile(String workspaceId, String dockSn, WaylineFileDTO metadata) {
        WaylineFileEntity file = this.dtoConvertToEntity(metadata);
        file.setWaylineId(UUID.randomUUID().toString());
        file.setWorkspaceId(workspaceId);
        file.setDockSn(dockSn);

        if (!StringUtils.hasText(file.getSign())) {
            try (InputStream object = ossService.getObject(OssConfiguration.bucket, metadata.getObjectKey())) {
                if (object.available() == 0) {
                    throw new RuntimeException("The file " + metadata.getObjectKey() +
                            " does not exist in the bucket[" + OssConfiguration.bucket + "].");
                }
                file.setSign(DigestUtils.md5DigestAsHex(object));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        int insertId = mapper.insert(file);
        return insertId > 0 ? file.getWaylineId() : null;
    }

    public Integer saveWaylineFileToDock(String workspaceId, String dockSn, WaylineFileDTO metadata) {
        WaylineFileEntity file = this.dtoConvertToEntity(metadata);
        file.setWaylineId(UUID.randomUUID().toString());
        file.setWorkspaceId(workspaceId);
        file.setDockSn(dockSn);

        if (!StringUtils.hasText(file.getSign())) {
            try (InputStream object = ossService.getObject(OssConfiguration.bucket, metadata.getObjectKey())) {
                if (object.available() == 0) {
                    throw new RuntimeException("The file " + metadata.getObjectKey() +
                            " does not exist in the bucket[" + OssConfiguration.bucket + "].");
                }
                file.setSign(DigestUtils.md5DigestAsHex(object));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        int insertId = mapper.insert(file);
        return insertId > 0 ? file.getId() : insertId;
    }

    @Override
    public Boolean markFavorite(String workspaceId, List<String> waylineIds, Boolean isFavorite) {
        if (waylineIds.isEmpty()) {
            return false;
        }
        if (isFavorite == null) {
            return true;
        }
        return mapper.update(null, new LambdaUpdateWrapper<WaylineFileEntity>()
                .set(WaylineFileEntity::getFavorited, isFavorite)
                .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                .in(WaylineFileEntity::getWaylineId, waylineIds)) > 0;
    }

    @Override
    public List<String> getDuplicateNames(String workspaceId, List<String> names) {
        return mapper.selectList(new LambdaQueryWrapper<WaylineFileEntity>()
                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                        .in(WaylineFileEntity::getName, names))
                .stream()
                .map(WaylineFileEntity::getName)
                .collect(Collectors.toList());
    }

    @Override
    public boolean getDuplicateName(String workspaceId, String dockSn, String name) {
        return !mapper.selectList(new LambdaQueryWrapper<WaylineFileEntity>()
                .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                .eq(WaylineFileEntity::getDockSn, dockSn)
                .eq(WaylineFileEntity::getName, name)).isEmpty();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateWayline(UpdateWaylineDTO dto) {
        Optional<WaylineFileDTO> waylineFileDTO = validKmzFile(dto.getFile(),dto.getWorkspaceId());
        if (waylineFileDTO.isEmpty()) {
            throw new RuntimeException("航线文件格式不正确");
        }
        WaylineFileEntity waylineFileEntity = mapper.selectOne(new LambdaQueryWrapper<WaylineFileEntity>().eq(WaylineFileEntity::getWaylineId, dto.getWaylineId()));
        if (waylineFileEntity == null) {
            return false;
        }
        ossService.deleteObject(OssConfiguration.bucket, waylineFileEntity.getObjectKey());
        try {
            ossService.putObject(OssConfiguration.bucket, waylineFileDTO.get().getObjectKey(), dto.getFile().getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        BeanUtils.copyProperties(dto, waylineFileEntity);
        waylineFileEntity.setSign(waylineFileDTO.get().getSign());
        waylineFileEntity.setObjectKey(waylineFileDTO.get().getObjectKey());
        waylineFileEntity.setPayloadModelKeys(String.join(",", waylineFileDTO.get().getPayloadModelKeys()));
        waylineFileEntity.setDroneModelKey(waylineFileDTO.get().getDroneModelKey());
        int updateCount = mapper.updateById(waylineFileEntity);
        return updateCount > 0;
    }

    @Override
    public Boolean deleteByWaylineId(String workspaceId, String waylineId) {
//        Optional<GetWaylineListResponse> waylineOpt = this.getWaylineByWaylineId(workspaceId, waylineId);
//        if (waylineOpt.isEmpty()) {
//            return true;
//        }
        WaylineFileEntity waylineFileEntity = mapper.selectOne(new LambdaQueryWrapper<WaylineFileEntity>().eq(WaylineFileEntity::getWaylineId, waylineId));
        var dockSn = waylineFileEntity.getDockSn();

        var onlineWayline = waylineRedisServiceImpl.getRunningJob(dockSn);
        if(onlineWayline.isPresent() && onlineWayline.get().getFileId().equals(waylineId)) {
            throw new RuntimeException("航线正在执行中,删除失败");
        }
        waylineFileEntity.setName(waylineFileEntity.getName()+"_deleted");
        waylineFileEntity.setIsDelete(true);
        mapper.updateById(waylineFileEntity);
        return true;
    }

    @Override
    public boolean batchDeleteByIds(String workspaceId, List<String> waylineIds) {
        if (!CollectionUtils.isEmpty(waylineIds)) {
            waylineIds.forEach(x -> deleteByWaylineId(workspaceId, x));
        }
        return true;
    }



    @Override
    public void importKmzFile(MultipartFile file, String workspaceId, String creator) {
        Optional<WaylineFileDTO> waylineFileOpt = validKmzFile(file,workspaceId);
        if (waylineFileOpt.isEmpty()) {
            throw new RuntimeException("航线文件格式不正确");
        }

        try {
            WaylineFileDTO waylineFile = waylineFileOpt.get();
            waylineFile.setUsername(creator);

            ossService.putObject(OssConfiguration.bucket, waylineFile.getObjectKey(), file.getInputStream());
            this.saveWaylineFile(workspaceId, waylineFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean getValidateWaylineName(String workspaceId,String waylineName) {
        return mapper.selectOne(new LambdaQueryWrapper<WaylineFileEntity>()
                        .eq(WaylineFileEntity::getWorkspaceId, workspaceId)
                .eq(WaylineFileEntity::getName, waylineName)) == null;
    }
    @Override
    public String importMap2dKmzFile(MultipartFile file, Map2DWaylineParm param, String creator, DeviceDTO deviceDetail) {
        if (file.isEmpty()) {
            throw new RuntimeException("航线生成失败");
        }
        try {
            WaylineFileDTO waylineFile = WaylineFileDTO.builder()
                    .sign(DigestUtils.md5DigestAsHex(file.getInputStream()))
                    .objectKey(OssConfiguration.objectDirPrefix + "/" +param.getWorkspaceId()+"/"+UUID.randomUUID().toString()+"/" + param.getFileName()+".kmz")
                    .name(param.getFileName())
                    .droneModelKey(deviceDetail!=null?DeviceEnum.find(DeviceDomainEnum.DRONE, deviceDetail.getType(), deviceDetail.getSubType()).getDevice():DeviceEnum.find(DeviceDomainEnum.DRONE, DeviceTypeEnum.M3D, DeviceSubTypeEnum.ONE).getDevice())
                    .payloadModelKeys(deviceDetail==null?List.of(DeviceEnum.find(DeviceDomainEnum.PAYLOAD, DeviceTypeEnum.M3TD_CAMERA, DeviceSubTypeEnum.ZERO).getDevice()):List.of(DeviceEnum.find(DeviceDomainEnum.PAYLOAD, DroneTypeConstants.findDeviceInfoByKey(deviceDetail.getDeviceName()).getDeviceType(), DroneTypeConstants.findDeviceInfoByKey(deviceDetail.getDeviceName()).getDeviceSubType()).getDevice()))
                    .templateTypes(List.of(WaylineTypeEnum.find(1).getValue()))
                    .build();
            if (!this.getValidateWaylineName(param.getWorkspaceId(),waylineFile.getName())) {
                throw new RuntimeException("航线文件名重复");
            }
            waylineFile.setUsername(creator);
            waylineFile.setIsSave(1);
            ossService.putObject(OssConfiguration.bucket, waylineFile.getObjectKey(), file.getInputStream());
            return this.saveWaylineFile(param.getWorkspaceId(), param.getDockSn(), waylineFile);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }
    @Override
    public String importMap3dKmzFile(MultipartFile file, Map3DWaylineParam param, String creator, DeviceDTO deviceDetail){
        if (file.isEmpty()) {
            throw new RuntimeException("航线生成失败");
        }
        try {
            WaylineFileDTO waylineFile = WaylineFileDTO.builder()
                    .sign(DigestUtils.md5DigestAsHex(file.getInputStream()))
                    .objectKey(OssConfiguration.objectDirPrefix + "/" +param.getWorkspaceId()+"/"+UUID.randomUUID().toString()+"/" + param.getFileName()+".kmz")
                    .name(param.getFileName())
                    .droneModelKey(deviceDetail!=null?DeviceEnum.find(DeviceDomainEnum.DRONE, deviceDetail.getType(), deviceDetail.getSubType()).getDevice():DeviceEnum.find(DeviceDomainEnum.DRONE, DeviceTypeEnum.M3D, DeviceSubTypeEnum.ONE).getDevice())
                    .payloadModelKeys(deviceDetail==null?List.of(DeviceEnum.find(DeviceDomainEnum.PAYLOAD, DeviceTypeEnum.M3TD_CAMERA, DeviceSubTypeEnum.ZERO).getDevice()):List.of(DeviceEnum.find(DeviceDomainEnum.PAYLOAD, DroneTypeConstants.findDeviceInfoByKey(deviceDetail.getDeviceName()).getDeviceType(), DroneTypeConstants.findDeviceInfoByKey(deviceDetail.getDeviceName()).getDeviceSubType()).getDevice()))
                    .templateTypes(List.of(WaylineTypeEnum.find(2).getValue()))
                    .build();
            if (!this.getValidateWaylineName(param.getWorkspaceId(),waylineFile.getName())) {
                throw new RuntimeException("航线文件名重复");
            }
            waylineFile.setUsername(creator);
            waylineFile.setIsSave(1);
            ossService.putObject(OssConfiguration.bucket, waylineFile.getObjectKey(), file.getInputStream());
            return this.saveWaylineFile(param.getWorkspaceId(), param.getDockSn(), waylineFile);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public String importKmzInputStreamToDock(MultipartFile file, String dockSn, String workspaceId, String creator, String fileName, Integer isSave) {
        Optional<WaylineFileDTO> waylineFileOpt = validKmzFile(file,workspaceId);
        if (waylineFileOpt.isEmpty()) {
            throw new RuntimeException("航线文件格式不正确");
        }
        try {
            WaylineFileDTO waylineFile = waylineFileOpt.get();
//            if (!this.getValidateWaylineName(workspaceId,waylineFileOpt.get().getName())) {
//                throw new RuntimeException("航线文件名重复");
//            }
            waylineFile.setUsername(creator);
            waylineFile.setIsSave(isSave);
            ossService.putObject(OssConfiguration.bucket, waylineFile.getObjectKey(), file.getInputStream());
            return this.saveWaylineFile(workspaceId, dockSn, waylineFile);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public void importKmzFileToDock(MultipartFile file, String dockSn, String workspaceId, String creator) {
        Optional<WaylineFileDTO> waylineFileOpt = validKmzFile(file,workspaceId);
        if (waylineFileOpt.isEmpty()) {
            throw new RuntimeException("航线文件格式不正确");
        }

        try {
            WaylineFileDTO waylineFile = waylineFileOpt.get();
            waylineFile.setUsername(creator);
            ossService.putObject(OssConfiguration.bucket, waylineFile.getObjectKey(), file.getInputStream());
            this.saveWaylineFileToDock(workspaceId, dockSn, waylineFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private Optional<WaylineFileDTO> validKmzInputStream(InputStream kmzInputStream, String fileName) {
        try (ZipInputStream unzipFile = new ZipInputStream(kmzInputStream, StandardCharsets.UTF_8)) {

            ZipEntry nextEntry = unzipFile.getNextEntry();
            while (Objects.nonNull(nextEntry)) {
                boolean isWaylines = (KmzFileProperties.FILE_DIR_FIRST + "/" + KmzFileProperties.FILE_DIR_SECOND_TEMPLATE).equals(nextEntry.getName());
                if (!isWaylines) {
                    nextEntry = unzipFile.getNextEntry();
                    continue;
                }
                SAXReader reader = new SAXReader();
                Document document = reader.read(unzipFile);
                if (!StandardCharsets.UTF_8.name().equals(document.getXMLEncoding())) {
                    throw new RuntimeException("文件编码格式不正确");
                }

                Node droneNode = document.selectSingleNode("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_INFO);
                Node payloadNode = document.selectSingleNode("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_INFO);
                if (Objects.isNull(droneNode) || Objects.isNull(payloadNode)) {
                    throw new RuntimeException("航线文件格式不正确");
                }

                DeviceTypeEnum type = DeviceTypeEnum.find(Integer.parseInt(droneNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_ENUM_VALUE)));
                DeviceSubTypeEnum subType = DeviceSubTypeEnum.find(Integer.parseInt(droneNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_SUB_ENUM_VALUE)));
                DeviceTypeEnum payloadType = DeviceTypeEnum.find(Integer.parseInt(payloadNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_ENUM_VALUE)));
//                DeviceSubTypeEnum payloadSubType = DeviceSubTypeEnum.find(Integer.parseInt(payloadNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_SUB_ENUM_VALUE)));
                String templateType = document.valueOf("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_TEMPLATE_TYPE);

                return Optional.of(WaylineFileDTO.builder()
                        .droneModelKey(DeviceEnum.find(DeviceDomainEnum.DRONE, type, subType).getDevice())
                        .payloadModelKeys(List.of(DeviceEnum.find(DeviceDomainEnum.PAYLOAD, payloadType, DeviceSubTypeEnum.ZERO).getDevice()))
//                        .objectKey(OssConfiguration.objectDirPrefix + File.separator + filename)
                        .objectKey(OssConfiguration.objectDirPrefix + "/" + fileName)
                        .name(fileName.substring(0, fileName.lastIndexOf(WAYLINE_FILE_SUFFIX)))
                        .sign(DigestUtils.md5DigestAsHex(kmzInputStream))
                        .templateTypes(List.of(WaylineTypeEnum.find(templateType).getValue()))
                        .build());
            }

        } catch (IOException | DocumentException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private Optional<WaylineFileDTO> validKmzFile(MultipartFile file,String workspaceId) {
        String filename = file.getOriginalFilename();
        if (Objects.nonNull(filename) && !filename.endsWith(WAYLINE_FILE_SUFFIX)) {
            throw new RuntimeException("航线文件格式不正确");
        }
        try (ZipInputStream unzipFile = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {

            ZipEntry nextEntry = unzipFile.getNextEntry();
            while (Objects.nonNull(nextEntry)) {
                boolean isWaylines = (KmzFileProperties.FILE_DIR_FIRST + "/" + KmzFileProperties.FILE_DIR_SECOND_TEMPLATE).equals(nextEntry.getName());
                if (!isWaylines) {
                    nextEntry = unzipFile.getNextEntry();
                    continue;
                }
                SAXReader reader = new SAXReader();
                Document document = reader.read(unzipFile);
                if (!StandardCharsets.UTF_8.name().equals(document.getXMLEncoding())) {
                    throw new RuntimeException("文件编码格式不正确");
                }

                Node droneNode = document.selectSingleNode("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_INFO);
                Node payloadNode = document.selectSingleNode("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_INFO);
                if (Objects.isNull(droneNode) || Objects.isNull(payloadNode)) {
                    throw new RuntimeException("航线文件格式不正确");
                }

                DeviceTypeEnum type = DeviceTypeEnum.find(Integer.parseInt(droneNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_ENUM_VALUE)));
                DeviceSubTypeEnum subType = DeviceSubTypeEnum.find(Integer.parseInt(droneNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_DRONE_SUB_ENUM_VALUE)));
                DeviceTypeEnum payloadType = DeviceTypeEnum.find(Integer.parseInt(payloadNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_ENUM_VALUE)));
//                DeviceSubTypeEnum payloadSubType = DeviceSubTypeEnum.find(Integer.parseInt(payloadNode.valueOf(KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_PAYLOAD_SUB_ENUM_VALUE)));
                String templateType = document.valueOf("//" + KmzFileProperties.TAG_WPML_PREFIX + KmzFileProperties.TAG_TEMPLATE_TYPE);

                return Optional.of(WaylineFileDTO.builder()
                        .droneModelKey(DeviceEnum.find(DeviceDomainEnum.DRONE, type, subType).getDevice())
                        .payloadModelKeys(List.of(DeviceEnum.find(DeviceDomainEnum.PAYLOAD, payloadType, DeviceSubTypeEnum.ZERO).getDevice()))
//                        .objectKey(OssConfiguration.objectDirPrefix + File.separator + filename)
                        .objectKey(OssConfiguration.objectDirPrefix + "/" +workspaceId+"/"+UUID.randomUUID().toString()+"/" + filename)
                        .name(filename.substring(0, filename.lastIndexOf(WAYLINE_FILE_SUFFIX)))
                        .sign(DigestUtils.md5DigestAsHex(file.getInputStream()))
                        .templateTypes(List.of(WaylineTypeEnum.find(templateType).getValue()))
                        .build());
            }

        } catch (IOException | DocumentException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    /**
     * Convert database entity objects into wayline data transfer object.
     *
     * @param entity
     * @return
     */
    private GetWaylineListResponse entityConvertToDTO(WaylineFileEntity entity) {
        if (entity == null) {
            return null;
        }
        return new GetWaylineListResponse()
                .setDroneModelKey(DeviceEnum.find(entity.getDroneModelKey()))
                .setFavorited(entity.getFavorited())
                .setName(entity.getName())
                .setPayloadModelKeys(entity.getPayloadModelKeys() != null ?
                        Arrays.stream(entity.getPayloadModelKeys().split(",")).map(DeviceEnum::find).collect(Collectors.toList()) : null)
                .setTemplateTypes(Arrays.stream(entity.getTemplateTypes().split(","))
                        .map(Integer::parseInt).map(WaylineTypeEnum::find)
                        .collect(Collectors.toList()))
                .setWaylineType(entity.getTemplateTypes())
                .setUsername(entity.getUsername())
                .setObjectKey(entity.getObjectKey())
                .setSign(entity.getSign())
                .setDockSn(entity.getDockSn())
                .setUpdateTime(entity.getUpdateTime())
                .setCreateTime(entity.getCreateTime())
                .setId(entity.getWaylineId());
    }

    /**
     * Convert the received wayline object into a database entity object.
     *
     * @param file
     * @return
     */
    private WaylineFileEntity dtoConvertToEntity(WaylineFileDTO file) {
        WaylineFileEntity.WaylineFileEntityBuilder builder = WaylineFileEntity.builder();

        if (file != null) {
            builder.droneModelKey(file.getDroneModelKey())
                    .name(file.getName())
                    .username(file.getUsername())
                    .objectKey(file.getObjectKey())
                    // Separate multiple payload data with ",".
                    .payloadModelKeys(String.join(",", file.getPayloadModelKeys()))
                    .templateTypes(file.getTemplateTypes().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(",")))
                    .favorited(file.getFavorited())
                    .sign(file.getSign())
                    .isSave(file.getIsSave() == null ? 1 : file.getIsSave())
                    .build();
        }

        return builder.build();
    }


    private WaylineFileListDTO entityConvertToListDTO(WaylineFileEntity entity) {
        if (entity == null) {
            return null;
        }
        WaylineFileListDTO dto = new WaylineFileListDTO();
        dto.setName(entity.getName());
        dto.setWaylineType(entity.getTemplateTypes());
        dto.setUsername(entity.getUsername());
        dto.setUpdateTime(entity.getUpdateTime());
        dto.setWaylineId(entity.getWaylineId());
        dto.setWorkspaceId(entity.getWorkspaceId());
        dto.setDockSn(entity.getDockSn());
        return dto;
    }

}
