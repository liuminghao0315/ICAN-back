# Redis 缓存使用说明

## 概述

本项目已集成 Redis 缓存，用于减少数据库查询次数，提高系统性能。缓存主要应用于以下场景：

1. **用户信息查询**：用户名、邮箱、权限等
2. **视频信息查询**：视频详情、视频列表
3. **分析结果查询**：分析结果详情、列表、统计数据
4. **分析任务查询**：任务详情、任务列表

## 缓存策略

### 缓存过期时间

- **用户相关缓存**：60 分钟
- **视频/任务/结果详情**：30 分钟
- **列表数据（第一页）**：10 分钟
- **统计数据**：10 分钟

### 缓存键命名规范

所有缓存键使用统一的前缀格式：

```
cache:{模块}:{类型}:{参数}
```

例如：
- `cache:user:username:admin`
- `cache:video:id:123:userId`
- `cache:result:stats:userId`

## 已实现的缓存功能

### 1. UserService（用户服务）

#### 缓存的方法：
- `getUserByUsername(String username)` - 根据用户名获取用户
- `getUserByEmail(String email)` - 根据邮箱获取用户
- `getUserPermissions(String userId)` - 获取用户权限

#### 缓存清除时机：
- 用户密码重置时，自动清除相关用户缓存

### 2. VideoService（视频服务）

#### 缓存的方法：
- `getVideoById(String videoId, String userId)` - 获取视频详情
- `getUserVideos(...)` - 获取用户视频列表（仅缓存第一页）

#### 缓存清除时机：
- 视频上传时，清除用户视频列表缓存
- 视频删除时，清除视频详情和列表缓存

### 3. AnalysisResultService（分析结果服务）

#### 缓存的方法：
- `getResultById(String resultId, String userId)` - 获取分析结果详情
- `getResultByTaskId(String taskId, String userId)` - 根据任务ID获取结果
- `getLatestResultByVideoId(String videoId, String userId)` - 获取视频最新结果
- `getUserResults(...)` - 获取用户分析结果列表（仅缓存第一页）
- `getUserAnalysisStats(String userId)` - 获取用户分析统计数据
- `getRiskDistribution(String userId)` - 获取风险分布统计

#### 缓存清除时机：
- 保存分析结果时，清除相关缓存
- 删除分析结果时，清除相关缓存

### 4. AnalysisTaskService（分析任务服务）

#### 缓存的方法：
- `getTaskById(String taskId, String userId)` - 获取任务详情
- `getUserTasks(...)` - 获取用户任务列表（仅缓存第一页）

#### 缓存清除时机：
- 创建任务时，清除用户任务列表缓存
- 更新任务状态时，清除任务详情和列表缓存

## 缓存工具类

### RedisCacheUtil

提供了以下常用方法：

```java
// 设置缓存
redisCacheUtil.set(key, value, expireMinutes);
redisCacheUtil.set(key, value); // 使用默认过期时间

// 获取缓存
Object value = redisCacheUtil.get(key);
T value = redisCacheUtil.get(key, Class<T> clazz);

// 删除缓存
redisCacheUtil.delete(key);
redisCacheUtil.deleteByPattern(pattern); // 批量删除

// 判断缓存是否存在
boolean exists = redisCacheUtil.exists(key);

// 获取并设置（如果不存在）
T value = redisCacheUtil.getOrSet(key, () -> {
    // 查询数据库的逻辑
    return queryFromDatabase();
});
```

## 配置说明

### application.properties

```properties
# Redis 配置
spring.data.redis.host=192.168.6.130
spring.data.redis.port=6379
spring.data.redis.password=246801qqqQQQ!
```

### Redis 配置类

`RedisConfiguration.java` 已配置：
- 使用 Jackson2JsonRedisSerializer 进行序列化
- 支持 LocalDateTime 等 Java 8 时间类型
- Key 使用 StringRedisSerializer
- Value 使用 JSON 序列化

## 性能优化建议

1. **合理设置过期时间**：
   - 频繁更新的数据使用较短的过期时间
   - 相对稳定的数据使用较长的过期时间

2. **缓存预热**：
   - 系统启动时，可以预加载热点数据到缓存

3. **缓存穿透防护**：
   - 对于查询结果为 null 的情况，也缓存一个空值（设置较短的过期时间）

4. **缓存雪崩防护**：
   - 为缓存过期时间添加随机值，避免大量缓存同时过期

5. **监控缓存命中率**：
   - 定期检查缓存命中率，优化缓存策略

## 注意事项

1. **数据一致性**：
   - 数据更新时，务必清除相关缓存
   - 确保缓存与数据库数据的一致性

2. **内存管理**：
   - 定期检查 Redis 内存使用情况
   - 设置合理的最大内存限制

3. **异常处理**：
   - 缓存操作失败不应影响主业务流程
   - 所有缓存操作都包含异常处理，失败时直接查询数据库

4. **列表缓存**：
   - 仅缓存第一页数据，避免内存占用过大
   - 分页查询不缓存，保证数据实时性

## 扩展建议

如果需要进一步优化，可以考虑：

1. **使用 Spring Cache 注解**：
   - 使用 `@Cacheable`、`@CacheEvict` 等注解简化代码

2. **多级缓存**：
   - 本地缓存（Caffeine）+ Redis 缓存

3. **缓存预热**：
   - 定时任务预热热点数据

4. **缓存监控**：
   - 集成缓存监控工具，实时查看缓存状态

