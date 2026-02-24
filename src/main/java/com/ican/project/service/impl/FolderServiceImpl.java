package com.ican.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ican.project.mapper.FolderMapper;
import com.ican.project.mapper.VideoMapper;
import com.ican.project.model.entity.Folder;
import com.ican.project.model.entity.Video;
import com.ican.project.model.vo.FolderVO;
import com.ican.project.service.FolderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FolderServiceImpl implements FolderService {

    @Autowired
    private FolderMapper folderMapper;

    @Autowired
    private VideoMapper videoMapper;

    @Override
    public List<FolderVO> getFolderTree(String userId) {
        // 1. 查询用户所有文件夹
        List<Folder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .orderByAsc(Folder::getSortOrder)
                        .orderByAsc(Folder::getGmtCreated)
        );

        // 2. 统计每个文件夹的视频数
        List<Map<String, Object>> countList = folderMapper.countVideosByFolder(userId);
        Map<String, Integer> countMap = new HashMap<>();
        for (Map<String, Object> m : countList) {
            String fid = (String) m.get("folderId");
            int cnt = ((Number) m.get("cnt")).intValue();
            countMap.put(fid, cnt);
        }

        // 3. 构建树
        List<FolderVO> voList = folders.stream().map(f -> {
            FolderVO vo = new FolderVO();
            vo.setId(f.getId());
            vo.setParentId(f.getParentId());
            vo.setName(f.getName());
            vo.setSortOrder(f.getSortOrder());
            vo.setIsSystem(f.getIsSystem());
            vo.setGmtCreated(f.getGmtCreated() != null ? f.getGmtCreated().toString() : null);
            vo.setVideoCount(countMap.getOrDefault(f.getId(), 0));
            vo.setChildren(new ArrayList<>());
            return vo;
        }).collect(Collectors.toList());

        // 按 parentId 分组
        Map<String, List<FolderVO>> childrenMap = voList.stream()
                .filter(v -> v.getParentId() != null)
                .collect(Collectors.groupingBy(FolderVO::getParentId));

        // 递归设置子节点并累加视频数
        for (FolderVO vo : voList) {
            List<FolderVO> children = childrenMap.getOrDefault(vo.getId(), Collections.emptyList());
            vo.setChildren(children);
        }

        // 递归累加子文件夹视频数到父级
        List<FolderVO> roots = voList.stream()
                .filter(v -> v.getParentId() == null)
                .collect(Collectors.toList());
        roots.forEach(this::accumulateVideoCount);

        // 4. 构建最终结果：虚拟节点 + 用户文件夹树
        List<FolderVO> result = new ArrayList<>();

        // "全部记录"虚拟节点
        int totalVideos = folderMapper.countAllVideos(userId);
        FolderVO allRecords = FolderVO.builder()
                .id("__ALL__")
                .name("全部记录")
                .isSystem(true)
                .videoCount(totalVideos)
                .children(Collections.emptyList())
                .build();
        result.add(allRecords);

        // "未分类"虚拟节点
        int uncategorized = folderMapper.countUncategorizedVideos(userId);
        FolderVO uncategorizedFolder = FolderVO.builder()
                .id("__UNCATEGORIZED__")
                .name("未分类")
                .isSystem(true)
                .videoCount(uncategorized)
                .children(Collections.emptyList())
                .build();
        result.add(uncategorizedFolder);

        // 用户自定义文件夹树
        result.addAll(roots);

        return result;
    }

    /** 递归累加子文件夹视频数 */
    private int accumulateVideoCount(FolderVO vo) {
        int total = vo.getVideoCount() != null ? vo.getVideoCount() : 0;
        if (vo.getChildren() != null) {
            for (FolderVO child : vo.getChildren()) {
                total += accumulateVideoCount(child);
            }
        }
        vo.setVideoCount(total);
        return total;
    }

    @Override
    @Transactional
    public FolderVO createFolder(String userId, String parentId, String name) {
        // 校验同级不重名
        checkDuplicateName(userId, parentId, name, null);

        // 校验父文件夹存在（如果指定了parentId）
        if (parentId != null) {
            Folder parent = folderMapper.selectById(parentId);
            if (parent == null || !parent.getUserId().equals(userId)) {
                throw new RuntimeException("父文件夹不存在");
            }
        }

        Folder folder = Folder.builder()
                .userId(userId)
                .parentId(parentId)
                .name(name.trim())
                .sortOrder(0)
                .isSystem(false)
                .build();
        folderMapper.insert(folder);

        FolderVO vo = new FolderVO();
        vo.setId(folder.getId());
        vo.setParentId(folder.getParentId());
        vo.setName(folder.getName());
        vo.setSortOrder(folder.getSortOrder());
        vo.setIsSystem(false);
        vo.setVideoCount(0);
        vo.setChildren(new ArrayList<>());
        return vo;
    }

    @Override
    @Transactional
    public void renameFolder(String userId, String folderId, String newName) {
        Folder folder = getAndCheckOwnership(userId, folderId);
        if (Boolean.TRUE.equals(folder.getIsSystem())) {
            throw new RuntimeException("系统目录不可重命名");
        }
        checkDuplicateName(userId, folder.getParentId(), newName, folderId);
        folder.setName(newName.trim());
        folderMapper.updateById(folder);
    }

    @Override
    @Transactional
    public void moveFolder(String userId, String folderId, String newParentId, int sortOrder) {
        Folder folder = getAndCheckOwnership(userId, folderId);
        if (Boolean.TRUE.equals(folder.getIsSystem())) {
            throw new RuntimeException("系统目录不可移动");
        }
        // 不能移动到自身或子文件夹下
        if (folderId.equals(newParentId)) {
            throw new RuntimeException("不能移动到自身");
        }
        List<String> descendants = getAllDescendantFolderIds(userId, folderId);
        if (newParentId != null && descendants.contains(newParentId)) {
            throw new RuntimeException("不能移动到子文件夹下");
        }
        // 校验目标位置不重名（仅当父级变化时）
        boolean parentChanged = !java.util.Objects.equals(folder.getParentId(), newParentId);
        if (parentChanged) {
            checkDuplicateName(userId, newParentId, folder.getName(), folderId);
        }

        String oldParentId = folder.getParentId();

        // 更新父级
        folder.setParentId(newParentId);
        folderMapper.updateById(folder);

        // 对新父级下的所有兄弟节点（含自身）做完整重排
        reorderSiblingsComplete(userId, newParentId, folderId, sortOrder);

        // 如果父级发生了变化，还需要对旧父级做紧凑重排（填补空洞）
        if (parentChanged) {
            compactSiblings(userId, oldParentId, folderId);
        }
    }

    /**
     * 完整重排：把 folderId 插入到 sortOrder 位置，其余节点顺序不变，统一从 0 开始重新编号
     */
    private void reorderSiblingsComplete(String userId, String parentId, String folderId, int insertOrder) {
        // 查询同级其他节点（按当前 sortOrder 排序）
        LambdaQueryWrapper<Folder> wrapper = new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .ne(Folder::getId, folderId)
                .orderByAsc(Folder::getSortOrder)
                .orderByAsc(Folder::getGmtCreated);
        if (parentId != null) {
            wrapper.eq(Folder::getParentId, parentId);
        } else {
            wrapper.isNull(Folder::getParentId);
        }
        List<Folder> others = folderMapper.selectList(wrapper);

        // 在 insertOrder 位置插入占位，然后统一从 0 重新编号
        // 先给 others 按 0,1,2... 临时编号，在 insertOrder 处插入 folderId
        List<String> orderedIds = new ArrayList<>();
        for (Folder f : others) {
            orderedIds.add(f.getId());
        }
        // 确保 insertOrder 不越界，insertOrder 是期望的最终位置（0-based）
        int insertAt = Math.max(0, Math.min(insertOrder, orderedIds.size()));
        orderedIds.add(insertAt, folderId);

        // 按新顺序统一更新 sortOrder
        for (int i = 0; i < orderedIds.size(); i++) {
            folderMapper.update(null,
                    new LambdaUpdateWrapper<Folder>()
                            .eq(Folder::getId, orderedIds.get(i))
                            .set(Folder::getSortOrder, i)
            );
        }
    }

    /**
     * 紧凑重排：对 parentId 下的节点（排除 excludeId）从 0 开始重新编号，填补空洞
     */
    private void compactSiblings(String userId, String parentId, String excludeId) {
        LambdaQueryWrapper<Folder> wrapper = new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .ne(Folder::getId, excludeId)
                .orderByAsc(Folder::getSortOrder)
                .orderByAsc(Folder::getGmtCreated);
        if (parentId != null) {
            wrapper.eq(Folder::getParentId, parentId);
        } else {
            wrapper.isNull(Folder::getParentId);
        }
        List<Folder> siblings = folderMapper.selectList(wrapper);
        for (int i = 0; i < siblings.size(); i++) {
            Folder f = siblings.get(i);
            if (f.getSortOrder() != i) {
                folderMapper.update(null,
                        new LambdaUpdateWrapper<Folder>()
                                .eq(Folder::getId, f.getId())
                                .set(Folder::getSortOrder, i)
                );
            }
        }
    }

    @Override
    @Transactional
    public int deleteFolder(String userId, String folderId) {
        Folder folder = getAndCheckOwnership(userId, folderId);
        if (Boolean.TRUE.equals(folder.getIsSystem())) {
            throw new RuntimeException("系统目录不可删除");
        }

        // 获取该文件夹及所有子文件夹ID（递归）
        List<String> allFolderIds = getAllDescendantFolderIds(userId, folderId);
        allFolderIds.add(folderId);

        // 统计这些文件夹下的视频总数
        Long videoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .in(Video::getFolderId, allFolderIds)
                        .eq(Video::getUserId, userId)
        );
        int movedCount = videoCount != null ? videoCount.intValue() : 0;

        // 将视频移至未分类（folder_id 置 null）
        if (movedCount > 0) {
            videoMapper.update(null,
                    new LambdaUpdateWrapper<Video>()
                            .in(Video::getFolderId, allFolderIds)
                            .eq(Video::getUserId, userId)
                            .set(Video::getFolderId, null)
            );
        }

        // 删除所有相关文件夹
        folderMapper.deleteBatchIds(allFolderIds);

        return movedCount;
    }

    @Override
    @Transactional
    public void moveVideosToFolder(String userId, List<String> videoIds, String folderId) {
        // 校验目标文件夹存在
        if (folderId != null) {
            Folder folder = folderMapper.selectById(folderId);
            if (folder == null || !folder.getUserId().equals(userId)) {
                throw new RuntimeException("目标文件夹不存在");
            }
        }
        videoMapper.update(null,
                new LambdaUpdateWrapper<Video>()
                        .in(Video::getId, videoIds)
                        .eq(Video::getUserId, userId)
                        .set(Video::getFolderId, folderId)
        );
    }

    @Override
    public List<String> getAllDescendantFolderIds(String userId, String folderId) {
        List<String> result = new ArrayList<>();
        List<Folder> children = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getParentId, folderId)
        );
        for (Folder child : children) {
            result.add(child.getId());
            result.addAll(getAllDescendantFolderIds(userId, child.getId()));
        }
        return result;
    }

    /** 校验同级目录不重名 */
    private void checkDuplicateName(String userId, String parentId, String name, String excludeId) {
        LambdaQueryWrapper<Folder> wrapper = new LambdaQueryWrapper<Folder>()
                .eq(Folder::getUserId, userId)
                .eq(Folder::getName, name.trim());
        if (parentId != null) {
            wrapper.eq(Folder::getParentId, parentId);
        } else {
            wrapper.isNull(Folder::getParentId);
        }
        if (excludeId != null) {
            wrapper.ne(Folder::getId, excludeId);
        }
        if (folderMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("同级目录下已存在同名文件夹「" + name.trim() + "」");
        }
    }

    /** 获取文件夹并校验归属 */
    private Folder getAndCheckOwnership(String userId, String folderId) {
        Folder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new RuntimeException("文件夹不存在");
        }
        return folder;
    }
}
