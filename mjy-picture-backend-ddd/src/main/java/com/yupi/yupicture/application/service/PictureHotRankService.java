package com.yupi.yupicture.application.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yupicture.domain.picture.entity.Picture;
import com.yupi.yupicture.domain.picture.valueobject.PictureReviewStatusEnum;
import com.yupi.yupicture.interfaces.dto.picture.PictureQueryRequest;
import com.yupi.yupicture.interfaces.vo.picture.PictureVO;
import com.yupi.yupicture.shared.cache.MultiLevelCacheService;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PictureHotRankService {

    private static final String HOT_RANK_KEY = "yupicture:picture:hot:rank";

    private static final String HOT_RANK_CACHE_KEY_PREFIX = "yupicture:picture:hot:rank:vo:";

    private static final Duration HOT_RANK_CACHE_TTL = Duration.ofSeconds(30);

    private static final int SEED_RANK_SIZE = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PictureApplicationService pictureApplicationService;

    @Resource
    private MultiLevelCacheService multiLevelCacheService;

    public void recordView(Picture picture) {
        if (picture == null || picture.getId() == null || picture.getSpaceId() != null
                || !Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
            return;
        }
        stringRedisTemplate.opsForZSet().incrementScore(HOT_RANK_KEY, String.valueOf(picture.getId()), 1D);
    }

    public List<PictureVO> listHotPictures(int topN, HttpServletRequest request) {
        String cacheKey = HOT_RANK_CACHE_KEY_PREFIX + topN;
        String cachedValue = multiLevelCacheService.getOrLoadWithMutex(cacheKey, HOT_RANK_CACHE_TTL,
                () -> JSONUtil.toJsonStr(loadHotPictures(topN, request)));
        return JSONUtil.toList(cachedValue, PictureVO.class);
    }

    private List<PictureVO> loadHotPictures(int topN, HttpServletRequest request) {
        Set<String> pictureIdSet = stringRedisTemplate.opsForZSet().reverseRange(HOT_RANK_KEY, 0, topN - 1L);
        if (pictureIdSet == null || pictureIdSet.isEmpty()) {
            seedRank(topN);
            pictureIdSet = stringRedisTemplate.opsForZSet().reverseRange(HOT_RANK_KEY, 0, topN - 1L);
        }
        if (pictureIdSet == null || pictureIdSet.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> pictureIds = pictureIdSet.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        Map<Long, Picture> pictureMap = new HashMap<>();
        for (Picture picture : pictureApplicationService.listByIds(pictureIds)) {
            if (picture.getSpaceId() == null
                    && Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
                pictureMap.put(picture.getId(), picture);
            }
        }
        List<Picture> pictureList = pictureIds.stream()
                .map(pictureMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Page<Picture> picturePage = new Page<>(1, topN, pictureList.size());
        picturePage.setRecords(pictureList);
        return pictureApplicationService.getPictureVOPage(picturePage, request).getRecords();
    }

    private void seedRank(int topN) {
        int seedSize = Math.max(topN, SEED_RANK_SIZE);
        PictureQueryRequest queryRequest = new PictureQueryRequest();
        queryRequest.setCurrent(1);
        queryRequest.setPageSize(seedSize);
        queryRequest.setNullSpaceId(true);
        queryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        queryRequest.setSortField("createTime");
        queryRequest.setSortOrder("descend");
        Page<Picture> picturePage = pictureApplicationService.page(new Page<>(1, seedSize, false),
                pictureApplicationService.getQueryWrapper(queryRequest));
        List<Picture> pictureList = picturePage.getRecords();
        for (int index = 0; index < pictureList.size(); index++) {
            Picture picture = pictureList.get(index);
            double initialScore = pictureList.size() - index;
            stringRedisTemplate.opsForZSet().add(HOT_RANK_KEY, String.valueOf(picture.getId()), initialScore);
        }
    }
}