package com.dji.zc.cloud.wayline.service;

import com.dji.sdk.cloudapi.wayline.GetWaylineListRequest;
import com.dji.sdk.cloudapi.wayline.GetWaylineListResponse;
import com.dji.sdk.common.PaginationData;
import com.dji.zc.cloud.manage.model.dto.DeviceDTO;
import com.dji.zc.cloud.wayline.model.dto.UpdateWaylineDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineFileDTO;
import com.dji.zc.cloud.wayline.model.dto.WaylineFileListDTO;
import com.dji.zc.cloud.wayline.model.param.Map2DWaylineParm;
import com.dji.zc.cloud.wayline.model.param.Map3DWaylineParam;
import com.dji.zc.cloud.wayline.model.param.WaylineQueryParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URL;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * @author sean
 * @version 0.3
 * @date 2021/12/22
 */
public interface IWaylineFileService {

    /**
     * Perform paging queries based on query parameters.
     * @param workspaceId
     * @param param
     * @return
     */
    PaginationData<GetWaylineListResponse> getWaylinesByParam(String workspaceId, String dockSn,GetWaylineListRequest param);

    PaginationData<GetWaylineListResponse> getWaylinesByParam(String workspaceId, String dockSn, WaylineQueryParam param);

    PaginationData<WaylineFileListDTO> getWaylinePageList(WaylineQueryParam request);
    /**
     * Query the information of this wayline file according to the wayline file id.
     * @param workspaceId
     * @param waylineId
     * @return
     */
    Optional<GetWaylineListResponse> getWaylineByWaylineId(String workspaceId, String waylineId);

    /**
     * Get the download address of the file object.
     * @param workspaceId
     * @param waylineId
     * @return
     */
    URL getObjectUrl(String workspaceId, String waylineId) throws SQLException;
    InputStream getObjectFile(String workspaceId, String waylineId) throws SQLException;
    /**
     * Save the basic information of the wayline file.
     * @param workspaceId
     * @param metadata
     * @return
     */
    Integer saveWaylineFile(String workspaceId, WaylineFileDTO metadata);

    /**
     * Updates whether the file is collected or not based on the passed parameters.
     * @param workspaceId
     * @param ids          wayline id
     * @param isFavorite   Whether the wayline file is favorited or not.
     * @return
     */
    Boolean markFavorite(String workspaceId, List<String> ids, Boolean isFavorite);

    /**
     * Batch query for duplicate file names in workspace.
     * @param workspaceId
     * @param names
     * @return
     */
    List<String> getDuplicateNames(String workspaceId, List<String> names);
    boolean updateWayline(UpdateWaylineDTO dto);
    /**
     * Delete the wayline file based on the wayline id.
     * @param workspaceId
     * @param waylineId
     */
    Boolean deleteByWaylineId(String workspaceId, String waylineId);

    /**
     * Import kmz wayline file.
     * @param file
     * @param workspaceId
     * @param creator
     * @return
     */
    void importKmzFile(MultipartFile file, String workspaceId, String creator);
    String importMap2dKmzFile(MultipartFile file, Map2DWaylineParm param, String creator, DeviceDTO deviceDetail);
    String importKmzInputStreamToDock(MultipartFile file, String dockSn, String workspaceId, String creator, String fileName,Integer isSave);
    void importKmzFileToDock(MultipartFile file, String dockSn,String workspaceId, String creator);

    boolean batchDeleteByIds(String workspaceId, List<String> waylineIds);

    boolean getDuplicateName(String workspaceId, String dockSn, String name);


    String importMap3dKmzFile(MultipartFile kmzFile, Map3DWaylineParam param, String creator, DeviceDTO deviceDetail);
}
