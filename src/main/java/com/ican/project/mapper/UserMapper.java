package com.ican.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ican.project.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Mapper
public interface UserMapper extends BaseMapper<User> {
    /**
     * 自定义关联查询：根据用户ID获取权限名列表
     * 方法名必须和 XML 中的 id 一致
     */
    List<String> selectPermsByUserId(@Param("userId") String userId);
}
