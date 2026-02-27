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

    /**
     * 插入用户角色关联记录
     * @param id 关联记录ID
     * @param userId 用户ID
     * @param roleId 角色ID
     * @return 插入的记录数
     */
    int insertUserRole(@Param("id") String id, @Param("userId") String userId, @Param("roleId") String roleId);
    
    /**
     * 增加用户分析次数（+1）
     * @param userId 用户ID
     * @return 更新的记录数
     */
    int incrementAnalysisCount(@Param("userId") String userId);
    
    /**
     * 获取用户分析次数
     * @param userId 用户ID
     * @return 分析次数
     */
    Integer getAnalysisCount(@Param("userId") String userId);

    /**
     * 根据用户ID查询角色名称列表
     */
    List<String> selectRoleNamesByUserId(@Param("userId") String userId);

    /**
     * 查询所有拥有指定角色的用户ID
     */
    List<String> selectUserIdsByRoleName(@Param("roleName") String roleName);
}
