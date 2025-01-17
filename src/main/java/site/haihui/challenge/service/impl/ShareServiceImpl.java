package site.haihui.challenge.service.impl;

import java.util.Date;
import java.util.HashMap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import site.haihui.challenge.common.auth.UserContext;
import site.haihui.challenge.common.exception.CommonException;
import site.haihui.challenge.entity.Feedback;
import site.haihui.challenge.entity.Round;
import site.haihui.challenge.entity.User;
import site.haihui.challenge.entity.WrongQuestionBook;
import site.haihui.challenge.mapper.FeedbackMapper;
import site.haihui.challenge.mapper.UserMapper;
import site.haihui.challenge.mapper.WrongQuestionBookMapper;
import site.haihui.challenge.service.IRedisService;
import site.haihui.challenge.service.IShareService;
import site.haihui.challenge.utils.StringUtils;
import site.haihui.challenge.utils.Time;
import site.haihui.challenge.vo.CaptchaVO;
import site.haihui.challenge.vo.IgscVO;

@Service
public class ShareServiceImpl implements IShareService {

    public static String userMaxScoreKey = "challenge:%s:userMaxScoreKey"; // 最高分

    public static String userMaxScoreRoundKey = "challenge:%s:userMaxScoreRoundKey"; // 最高分

    public static String userWrongQuestionKey = "challenge:%s:userWrongQuestionKey"; // 答错题

    public static String userWrongValidQuestionKey = "challenge:%s:userValidWrongQuestionKey"; // 错题本

    public static String userTotalQuestionKey = "challenge:%s:userTotalQuestionKey"; // 回答总数

    public static String userCacheKey = "challenge:%s:userCacheKeyNew"; // 用户信息缓存

    public static String userSimpleQuestionCacheKey = "challenge:%s:userSimpleQuestionCacheKey"; // 跳过题

    public static String userTodaySkipTimesCacheKey = "challenge:%s:%s:userTodaySkipTimesKey"; // 今日跳过数

    public static String userTodayHideTimesCacheKey = "challenge:%s:%s:userTodayHideTimesCacheKey"; // 今日隐藏题目

    public static String userCorrectCacheKey = "challenge:%s:userCorrectCacheKey"; // 答对题

    public static String userTodayTrainingCacheKey = "challenge:%s:%s:userTodayTrainingCacheKey"; // 今日训练

    public static String userTodayTotalTrainingCacheKey = "challenge:%s:%s:userTodayTotalTrainingCacheKey"; // 今日训练请求的题目数

    public static String userCurrentWeekMaxScore = "challenge:%s:%s:userCurrentWeekMaxScore";

    public static String userCurrentWeekMaxScoreRound = "challenge:%s:%s:userCurrentWeekMaxScoreRound";

    public static String zSetRankKey = "challenge:allrank";

    public static String zSetWeekRankKey = "challenge:weekrank:%s";

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private IRedisService<Integer> redisService;

    @Autowired
    private IRedisService<Object> redisService2;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WrongQuestionBookMapper wrongQuestionBookMapper;

    @Autowired
    private FeedbackMapper feedbackMapper;

    @Value("${captcha.host}")
    private String captchaHost;

    private String getCaptchaUrl = "user/oM_v54ibr_jH_3AB9AhFbTZr7sTc/captcha";

    private String checkCaptchUrl = "user/check_captcha?open_id={openid}&token={token}&captcha={captcha}";

    @Override
    public Integer getUserMaxScore(Integer uid, Integer type) {
        String key = getCacheKey(uid, type);
        Integer cachedData = redisService.get(key);
        return cachedData == null ? 0 : cachedData;
    }

    @Override
    public void setUserMaxScore(Integer uid, Integer score, Integer type) {
        String key = getCacheKey(uid, type);
        redisService.set(key, score);
    }

    @Override
    public Integer getCachedQuestionNum(Integer uid, Integer type) {
        String key = getCacheKey(uid, type);
        return redisService.get(key);
    }

    @Override
    public void incrCachedQuestionNum(Integer uid, Integer type, Integer fromWhere) {
        // 练习模式, 仅增加错题集数量
        if (fromWhere == 0) {
            if (type == 0) {
                String key = getCacheKey(uid, 5);
                redisService.increment(key, 1);
            }
        } else { // 挑战模式
            String key = getCacheKey(uid, type);
            redisService.increment(key, 1);
            if (type == 0) {
                key = getCacheKey(uid, 5);
                redisService.increment(key, 1);
            }
        }
    }

    @Override
    public void setCachedQuestionNum(Integer uid, Integer num, Integer type) {
        String key = getCacheKey(uid, type);
        redisService.set(key, num);
    }

    @Override
    public String getCacheKey(Integer uid, Integer type) {
        if (type == 0) {
            return String.format(userWrongQuestionKey, uid);
        } else if (type == 1) {
            return String.format(userTotalQuestionKey, uid);
        } else if (type == 2) {
            return String.format(userMaxScoreKey, uid);
        } else if (type == 3) {
            return String.format(userCacheKey, uid);
        } else if (type == 4) {
            return String.format(userSimpleQuestionCacheKey, uid);
        } else if (type == 5) {
            return String.format(userWrongValidQuestionKey, uid);
        } else if (type == 6) {
            return String.format(userCorrectCacheKey, uid);
        } else if (type == 7) {
            return String.format(userTodayTrainingCacheKey, uid,
                    Time.timestampToString(Time.currentTimeSeconds().intValue(), "yyyyMMdd"));
        } else if (type == 8) {
            return String.format(userTodayTotalTrainingCacheKey, uid,
                    Time.timestampToString(Time.currentTimeSeconds().intValue(), "yyyyMMdd"));
        } else if (type == 9) {
            return String.format(userTodayHideTimesCacheKey, uid,
                    Time.timestampToString(Time.currentTimeSeconds().intValue(), "yyyyMMdd"));
        } else if (type == 10) {
            return String.format(userCurrentWeekMaxScore, uid, Time.getCurrentWeekOfYear());
        } else if (type == 11) {
            return String.format(userMaxScoreRoundKey, uid);
        } else if (type == 12) {
            return String.format(userCurrentWeekMaxScoreRound, uid, Time.getCurrentWeekOfYear());
        }
        return "";
    }

    @Override
    public User getUser(Integer uid) {
        User user = (User) redisService2.get(getCacheKey(uid, 3));
        if (null == user) {
            user = userMapper.selectById(uid);
            if (null != user) {
                setCachedUser(uid, user);
            }
        }
        return user;
    }

    @Override
    public void setCachedUser(Integer uid, User user) {
        redisService2.set(getCacheKey(uid, 3), user);
    }

    @Override
    public void putQuestionSet(Integer uid, Integer questionId, Integer type) {
        String key = getCacheKey(uid, type);
        redisService.addSet(key, questionId);
    }

    @Override
    public boolean isQuesetionSet(Integer uid, Integer questionId, Integer type) {
        String key = getCacheKey(uid, type);
        return redisService.sIsMember(key, questionId);
    }

    @Override
    public Integer getSkipTimes(Integer uid) {
        String key = String.format(userTodaySkipTimesCacheKey, uid,
                Time.timestampToString(Time.currentTimeSeconds().intValue(), "yyyyMMdd"));
        return redisService.get(key);
    }

    @Override
    public void incrSkipTimes(Integer uid) {
        String key = String.format(userTodaySkipTimesCacheKey, uid,
                Time.timestampToString(Time.currentTimeSeconds().intValue(), "yyyyMMdd"));
        if (null == redisService.get(key)) {
            redisService.set(key, 1);
            return;
        }
        redisService.increment(key, 1);
    }

    @Override
    public Integer getWrongQuestion(Integer uid, Integer status) {
        QueryWrapper<WrongQuestionBook> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("uid", uid);
        if (status == 1) {
            queryWrapper.eq("status", 1);
        }
        return wrongQuestionBookMapper.selectList(queryWrapper).size();
    }

    @Override
    public void clearCache(String key) {
        redisService2.delete(key);
    }

    @Override
    public CaptchaVO getCaptcha() {
        ResponseEntity<IgscVO> responseEntity = restTemplate.getForEntity(captchaHost + getCaptchaUrl, IgscVO.class);
        IgscVO res = responseEntity.getBody();
        if (res.getCode() != 0) {
            throw new CommonException(res.getMsg());
        }
        CaptchaVO vo = new CaptchaVO();
        vo.setToken(res.getToken());
        vo.setImageData(res.getCaptcha());
        return vo;
    }

    private boolean checkCaptch(String token, String captcha) {
        HashMap<String, String> map = new HashMap<>();
        map.put("openid", "oM_v54ibr_jH_3AB9AhFbTZr7sTc");
        map.put("token", token);
        map.put("captcha", captcha);
        ResponseEntity<IgscVO> responseEntity = restTemplate.getForEntity(captchaHost + checkCaptchUrl, IgscVO.class,
                map);
        IgscVO res = responseEntity.getBody();
        if (res.getCode() != 0) {
            return false;
        }
        return true;
    }

    @Override
    public boolean feedback(Integer questionId, String token, String captcha, Integer type, String remark) {
        if (!checkCaptch(token, captcha)) {
            throw new CommonException("验证码不正确");
        }
        Feedback feedback = new Feedback();
        feedback.setType(type);
        feedback.setUid(UserContext.getCurrentUser().getId());
        feedback.setNickname(UserContext.getCurrentUser().getNickname());
        feedback.setRemark(remark);
        feedback.setCreateTime(new Date());
        feedback.setQuestionId(questionId);
        feedbackMapper.insert(feedback);
        return true;
    }

    @Override
    public Round getUserMaxScoreRound(Integer uid, Integer type) {
        String key = getCacheKey(uid, type);
        return (Round) redisService2.get(key);
    }

    @Override
    public void setUserMaxScoreRound(Integer uid, Round round, Integer type) {
        redisService2.set(getCacheKey(uid, type), round);
    }

    @Override
    public void setRankCache(Integer type, Round round) {
        Integer uid = round.getUid();
        // 周排名
        if (type == 0) {
            setUserMaxScore(uid, round.getScore(), 10);
            Round maxRound = getUserMaxScoreRound(uid, 12);
            String key = String.format(zSetWeekRankKey, Time.getCurrentWeekOfYear());
            if (null != maxRound) {
                redisService2.remZSet(key, maxRound);
            }
            redisService2.addZSet(key, round.getScore().doubleValue(), round);
            setUserMaxScoreRound(uid, round, 12);
        } else if (type == 1) {// 加入全部排名
            setUserMaxScore(uid, round.getScore(), 2);
            Round maxRound = getUserMaxScoreRound(uid, 11);
            if (null != maxRound) {
                redisService2.remZSet(zSetRankKey, maxRound);
            }
            redisService2.addZSet(zSetRankKey, round.getScore().doubleValue(), round);
            setUserMaxScoreRound(uid, round, 11);
        }
        // 删除排行榜缓存
        redisService2.delete(StringUtils.makeRankListCacheKey(0));
        redisService2.delete(StringUtils.makeRankListCacheKey(1));
    }
}
