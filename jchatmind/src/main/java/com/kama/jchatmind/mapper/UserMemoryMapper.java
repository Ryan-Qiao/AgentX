package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMemoryMapper {
    int insert(UserMemory userMemory);

    UserMemory selectById(String id);

    List<UserMemory> selectAll();

    List<UserMemory> selectEnabledGlobal(@Param("limit") int limit);

    int countExactDuplicate(
            @Param("title") String title,
            @Param("content") String content
    );

    int markUsedByIds(@Param("ids") List<String> ids);

    int updateById(UserMemory userMemory);

    int deleteById(String id);
}
