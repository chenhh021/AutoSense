package com.chh.autosense.repository;

import com.chh.autosense.domain.model.Device;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    default Device selectOneBySn(String sn) {
        return selectOneByQuery(QueryWrapper.create()
                .where("sn = ?", sn)
                .limit(1));
    }

    default java.util.List<Device> selectMine(long userId) {
        return selectListByQuery(QueryWrapper.create()
                .where("user_id = ?", userId)
                .orderBy("created_at", false));
    }
}
