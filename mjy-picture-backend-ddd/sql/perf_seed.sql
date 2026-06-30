-- =============================================================
-- 性能压测造数据脚本  (Step 0.1)
-- 作用：向 picture 表批量生成测试数据，构造高并发压测所需的数据规模
-- 目标库：yu_picture
-- 用法（任选其一）：
--   1) 命令行：  mysql -uroot -p123456 yu_picture < perf_seed.sql
--   2) 客户端：  source 本文件
-- 默认生成 50 万条「公共图库」数据（spaceId=NULL, reviewStatus=1，可被公共图库直接检索）
-- 调整数量：修改文件末尾 CALL gen_pictures(总条数, 单批条数)
-- =============================================================

USE yu_picture;

DROP PROCEDURE IF EXISTS gen_pictures;

DELIMITER //
CREATE PROCEDURE gen_pictures(IN p_total INT, IN p_batch INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE k INT DEFAULT 0;
    DECLARE v_cat VARCHAR(64);
    DECLARE v_fmt VARCHAR(32);
    DECLARE v_w INT;
    DECLARE v_h INT;

    WHILE i < p_total DO
        START TRANSACTION;
        SET k = 0;
        WHILE k < p_batch AND i < p_total DO
            SET v_cat = ELT(FLOOR(1 + RAND() * 9),
                            '风景', '动物', '美食', '人物', '建筑', '动漫', '游戏', '科技', '其他');
            SET v_fmt = ELT(FLOOR(1 + RAND() * 3), 'jpg', 'png', 'webp');
            SET v_w = FLOOR(400 + RAND() * 3000);
            SET v_h = FLOOR(400 + RAND() * 2000);
            INSERT INTO picture
                (url, name, introduction, category, tags, picSize, picWidth, picHeight, picScale, picFormat,
                 userId, createTime, editTime, reviewStatus, reviewMessage, reviewerId, reviewTime, spaceId, picColor)
            VALUES
                (CONCAT('https://picsum.photos/seed/', i, '/', v_w, '/', v_h),
                 CONCAT('测试图片_', LPAD(i, 8, '0')),
                 CONCAT('这是第 ', i, ' 张压测样例图片，用于性能基线测试'),
                 v_cat,
                 CONCAT('["', v_cat, '","高清","壁纸"]'),
                 FLOOR(50000 + RAND() * 5000000),
                 v_w, v_h, ROUND(v_w / v_h, 4), v_fmt,
                 FLOOR(1 + RAND() * 50),
                 NOW() - INTERVAL FLOOR(RAND() * 365) DAY,
                 NOW(),
                 1, '系统自动通过', 1, NOW(),
                 NULL,
                 CONCAT('0x', LPAD(CONV(FLOOR(RAND() * 16777215), 10, 16), 6, '0')));
            SET i = i + 1;
            SET k = k + 1;
        END WHILE;
        COMMIT;
    END WHILE;
END //
DELIMITER ;

-- 生成 50 万条，单批 2000（如需百万级改成 1000000）
CALL gen_pictures(500000, 2000);

-- 结果校验
SELECT COUNT(*) AS total_pictures FROM picture;
SELECT reviewStatus, COUNT(*) AS cnt FROM picture GROUP BY reviewStatus;
SELECT category, COUNT(*) AS cnt FROM picture GROUP BY category ORDER BY cnt DESC;
