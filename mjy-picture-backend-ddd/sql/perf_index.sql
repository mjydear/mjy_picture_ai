-- =============================================================
-- 性能优化索引脚本  (P1.0)
-- 目标库：yu_picture
-- 背景（基于 profiling 实测）：
--   公共图库列表查询 WHERE spaceId IS NULL AND reviewStatus=1 [ORDER BY createTime DESC]
--   - 按 createTime 排序时触发 filesort（对命中的 24 万行全排序），首页都要 6.5s
--   - 复合索引 (spaceId, reviewStatus, createTime) 让 WHERE 等值过滤 + 排序都走索引
--     实测首页排序查询 6.5s -> 0.0103s（约 635 倍）
--   - COUNT(*) 仍可能走 index_merge，靠 P1.1 缓存 total 根治（索引非银弹）
-- 用法：mysql -uroot -p**** yu_picture < perf_index.sql
-- =============================================================
USE yu_picture;

-- 公共/空间图库列表：等值过滤(spaceId, reviewStatus) + 排序(createTime)
CREATE INDEX idx_space_review_ct ON picture (spaceId, reviewStatus, createTime);

-- 更新统计信息，帮助优化器选对索引
ANALYZE TABLE picture;

-- 验证（应显示 Backward index scan，Extra 不再有 Using filesort）：
-- EXPLAIN SELECT * FROM picture
--   WHERE reviewStatus = 1 AND spaceId IS NULL AND isDelete = 0
--   ORDER BY createTime DESC LIMIT 0, 12;
