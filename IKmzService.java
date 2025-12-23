package com.dji.zc.cloud.wayline.service;

import com.dji.sdk.common.HttpResultResponse;
import com.dji.zc.cloud.wayline.model.dto.UpdateUavRouteReq;
import com.dji.zc.cloud.wayline.model.kml.UavRouteReq;
import com.dji.zc.cloud.wayline.model.param.Map2DWaylineParm;
import com.dji.zc.cloud.wayline.model.param.Map3DWaylineParam;
import com.dji.zc.cloud.wayline.model.wpml.Mapping2dParseDTO;
import com.dji.zc.cloud.wayline.model.wpml.Mapping3dParseDTO;

public interface IKmzService {
    /**
     * 编辑kmz文件
     */
    void updateKmz(UpdateUavRouteReq uavRouteReq);

    /**
     * 生成kmz文件(带航点)
     */
    void buildKmz(UavRouteReq uavRouteReq);
    HttpResultResponse parseKml(String waylineId,String workspaceId);
    Mapping2dParseDTO parseWpml(String waylineId,String workspaceId);
    void buildMap2DKmz(Map2DWaylineParm parm);

    void buildMap3DKmz(Map3DWaylineParam param);

    Mapping3dParseDTO parse3DWpml(String waylineId, String workspaceId);

}
