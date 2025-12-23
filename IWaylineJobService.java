package com.dji.zc.cloud.wayline.service;

import com.dji.sdk.common.PaginationData;
import com.dji.zc.cloud.control.model.param.TakeoffToPointParam;
import com.dji.zc.cloud.wayline.model.dto.JobStatusCountDTO;
import com.dji.zc.cloud.wayline.model.dto.TaskTypeDTO;
import com.dji.zc.cloud.wayline.model.dto.TopRouteDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineJobDTO;
import com.dji.zc.cloud.wayline.model.dto.query.WaylineJobQuery;
import com.dji.zc.cloud.wayline.model.entity.WaylineJobEntity;
import com.dji.zc.cloud.wayline.model.enums.WaylineJobStatusEnum;
import com.dji.zc.cloud.wayline.model.param.CreateJobParam;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author sean
 * @version 1.1
 * @date 2022/6/1
 */
public interface IWaylineJobService {

    /**
     * Create wayline job in the database.
     * @param param
     * @param workspaceId   user info
     * @param username      user info
     * @param beginTime     The time the job started.
     * @param endTime       The time the job ended.
     * @return
     */
    Optional<WaylineJobDTO> createWaylineJob(CreateJobParam param, String workspaceId, String username, Long beginTime, Long endTime);

    Optional<WaylineJobDTO> createWaylineJob(CreateJobParam param, String workspaceId, String username, Long beginTime, Long endTime,String batchId);
    Optional<WaylineJobDTO> createWaylineJob(TakeoffToPointParam param, String workspaceId, String sn, String username);
    /**
     * Create a sub-task based on the information of the parent task.
     * @param workspaceId
     * @param parentId
     * @return
     */
    Optional<WaylineJobDTO> createWaylineJobByParent(String workspaceId, String parentId);

    /**
     * Query wayline jobs based on conditions.
     * @param workspaceId
     * @param jobIds
     * @param status
     * @return
     */
    List<WaylineJobDTO> getJobsByConditions(String workspaceId, Collection<String> jobIds, WaylineJobStatusEnum status);

    /**
     * Query job information based on job id.
     * @param workspaceId
     * @param jobId
     * @return job information
     */
    Optional<WaylineJobDTO> getJobByJobId(String workspaceId, String jobId);

    Optional<WaylineJobDTO> getJobBySimpleJobId(String jobId);
    /**
     * Update job data.
     * @param dto
     * @return
     */
    Boolean updateJob(WaylineJobDTO dto);

    /**
     * Paginate through all jobs in this workspace.
     * @param workspaceId
     * @param page
     * @param pageSize
     * @return
     */
    PaginationData<WaylineJobDTO> getJobsByWorkspaceId(String workspaceId, long page, long pageSize);

    /**
     * Query the wayline execution status of the dock.
     * @param dockSn
     * @return
     */
    WaylineJobStatusEnum getWaylineState(String dockSn);

    PaginationData<WaylineJobDTO> getJobsFilter(String workspaceId, String dockSn, WaylineJobQuery query, long page, long pageSize);
    PaginationData<WaylineJobDTO> getJobDicFilter(String workspaceId, String dockSn, WaylineJobQuery query, long page, long pageSize);

    PaginationData<WaylineJobDTO> getPendingWaylines(String workspaceId, String dockSn,Long page,Long pageSize);

    Integer getJobCountByBatchId(String batchId);

    List<WaylineJobEntity> getJobsByBatchId(String batchId);
    String validateJobHasMediaZip(String jobId);
    void putMediaUrlToJob(String jobId,String mediaUrl);
    PaginationData<WaylineJobDTO> getWarningJobsByWorkspaceId(String workspaceId,String dockSn,String modelTypes,Long page, Long pageSize);

    WaylineJobEntity getById(String jobId);

    PaginationData<WaylineJobDTO> getJobsPageList(WaylineJobQuery query, Long page, Long pageSize);

    WaylineJobDTO getJobInfo(String jobId);

    TaskTypeDTO getTaskTypeCount();

    List<TopRouteDTO> getTop5Count();

    JobStatusCountDTO getStatusCount();

    List<JobStatusCountDTO> getStatusCountByMonth(String yearMonth);
}
