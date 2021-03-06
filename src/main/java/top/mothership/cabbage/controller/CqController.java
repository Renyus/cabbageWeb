package top.mothership.cabbage.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import top.mothership.cabbage.consts.ParameterEnum;
import top.mothership.cabbage.manager.CqManager;
import top.mothership.cabbage.manager.DayLilyManager;
import top.mothership.cabbage.pattern.CQCodePattern;
import top.mothership.cabbage.pattern.MpCommandPattern;
import top.mothership.cabbage.pattern.RegularPattern;
import top.mothership.cabbage.pojo.coolq.CqMsg;
import top.mothership.cabbage.service.*;
import top.mothership.cabbage.util.qq.SmokeUtil;

import javax.annotation.PostConstruct;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * CQ控制器，用于处理CQ消息
 *
 * @author QHS
 */
@RestController
public class CqController {


    private final CqServiceImpl cqService;
    private final SmokeUtil smokeUtil;
    private final CqAdminServiceImpl cqAdminService;
    private final MpServiceImpl mpService;
    private final CqManager cqManager;
    private final AnalyzeServiceImpl analyzeService;
    private final DayLilyManager dayLilyManager;
    private final ShadowSocksCmdServiceImpl shadowSocksCmdService;

    private Logger logger = LogManager.getLogger(this.getClass());

    /**
     * Spring构造方法自动注入
     *  @param cqService      Service层
     * @param smokeUtil      负责禁言的工具类
     * @param cqAdminService
     * @param mpService
     * @param cqManager
     * @param analyzeService
     * @param dayLilyManager
     * @param shadowSocksCmdService
     */
    @Autowired
    public CqController(CqServiceImpl cqService, SmokeUtil smokeUtil, CqAdminServiceImpl cqAdminService, MpServiceImpl mpService, CqManager cqManager, AnalyzeServiceImpl analyzeService, DayLilyManager dayLilyManager, ShadowSocksCmdServiceImpl shadowSocksCmdService) {
        this.cqService = cqService;
        this.smokeUtil = smokeUtil;
        this.cqAdminService = cqAdminService;
        this.mpService = mpService;
        this.cqManager = cqManager;
        this.analyzeService = analyzeService;
        this.dayLilyManager = dayLilyManager;
        this.shadowSocksCmdService = shadowSocksCmdService;
    }

    /**
     * Controller主方法
     *
     * @param cqMsg 传入的QQ消息
     * @throws Exception 抛出异常给AOP检测
     */
    @RequestMapping(value = "/cqAPI", method = RequestMethod.POST, produces = "application/json;charset=UTF-8")
    public void cqMsgParse(@RequestBody CqMsg cqMsg) throws Exception {
        //待整理业务逻辑
        switch (cqMsg.getPostType()) {
            case "message":
                //转义
                String msg = cqMsg.getMessage();
                msg = msg.replaceAll("&#91;", "[");
                msg = msg.replaceAll("&#93;", "]");
                msg = msg.replaceAll("&#44;", ",");
                cqMsg.setMessage(msg);
                String msgWithoutImage;
                Matcher imageMatcher = CQCodePattern.SINGLE_IMG.matcher(msg);
                if (imageMatcher.find()) {
                    //替换掉消息内所有图片
                    msgWithoutImage = imageMatcher.replaceAll("");
                } else {
                    msgWithoutImage = msg;
                }
                //识别消息类型，根据是否是群聊，加入禁言消息队列
                Matcher cmdMatcher = RegularPattern.REG_CMD_REGEX.matcher(msgWithoutImage);
                switch (cqMsg.getMessageType()) {
                    case "group":
                        smokeUtil.parseSmoke(cqMsg);
                        break;
                    default:
                        break;
                }
                if (cmdMatcher.find()) {
                    //如果检测到命令，直接把消息中的图片去掉，避免Service层进行后续处理
                    cqMsg.setMessage(msgWithoutImage);
                    String log = "";
                    switch (cqMsg.getMessageType()) {
                        case "group":
                            log += "群" + cqMsg.getGroupId() + "成员" + cqMsg.getUserId() + "发送了命令" + cqMsg.getMessage();
                            break;
                        case "discuss":
                            log += "讨论组" + cqMsg.getDiscussId() + "成员" + cqMsg.getUserId() + "发送了命令" + cqMsg.getMessage();
                            break;
                        case "private":
                            log += "用户" + cqMsg.getUserId() + "发送了命令" + cqMsg.getMessage();
                            break;
                        default:
                            break;
                    }
                    logger.info(log);

                    switch (cmdMatcher.group(1).toLowerCase(Locale.CHINA)) {
                        //处理命令
                        case "sudo":
                            cmdMatcher = RegularPattern.ADMIN_CMD_REGEX.matcher(msg);

                            if(!cmdMatcher.find()){return;}
                            //无视命令大小写
                            switch (cmdMatcher.group(1).toLowerCase(Locale.CHINA)) {
                                case "add":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME_LIST, ParameterEnum.ROLE});
                                    cqAdminService.addUserRole(cqMsg);
                                    break;
                                case "del":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME_LIST});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.ROLE});
                                    cqAdminService.delUserRole(cqMsg);
                                    break;
                                case "bg":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.FILENAME, ParameterEnum.URL});
                                    cqAdminService.addComponent(cqMsg);
                                    break;
                                case "recent":
                                case "rs":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqAdminService.recent(cqMsg);
                                    break;
                                case "afk":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.DAY, ParameterEnum.ROLE});
                                    cqAdminService.checkAfkPlayer(cqMsg);
                                    break;
                                case "smoke":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.AT});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.SECOND});
                                    cqAdminService.smoke(cqMsg);
                                    break;
                                case "listinvite":
                                    cqAdminService.listInvite(cqMsg);
                                    break;
                                case "handleinvite":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.FLAG});
                                    cqAdminService.handleInvite(cqMsg);
                                    break;
                                case "clearinvite":
                                    CqAdminServiceImpl.request.clear();
                                    cqMsg.setMessage("清除列表成功");
                                    cqManager.sendMsg(cqMsg);
                                    return;
                                case "钦点":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.QQ, ParameterEnum.ROLE});
                                    cqAdminService.appoint(cqMsg);
                                    break;
                                case "fp":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.SEARCH_PARAM});
                                    cqAdminService.firstPlace(cqMsg);
                                    break;
                                case "listmsg":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.AT});
                                    cqAdminService.listMsg(cqMsg);
                                    break;
                                case "searchplayer":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqAdminService.searchPlayer(cqMsg);
                                    break;
                                case "groupinfo":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.GROUPID});
                                    cqAdminService.groupInfo(cqMsg);
                                    break;
                                case "help":
                                    cqAdminService.help(cqMsg);
                                    break;
                                case "repeatstar":
                                    cqAdminService.getRepeatStar(cqMsg);
                                    break;
                                case "roleinfo":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.ROLE});
                                    cqAdminService.roleInfo(cqMsg);
                                    break;
                                case "unbind":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.QQ});
                                    cqAdminService.unbind(cqMsg);
                                    break;
                                case "score":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME, ParameterEnum.BEATMAP_ID});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqAdminService.score(cqMsg);
                                    break;
                                case "ping":
                                    cqAdminService.ping(cqMsg);
                                    break;
                                default:
                                    break;
                            }
                            break;
                        case "mp":
                            cmdMatcher = MpCommandPattern.MP_CMD_REGEX.matcher(msg);
                            cmdMatcher.find();
                            switch (cmdMatcher.group(1).toLowerCase(Locale.CHINA)) {
                                case "rs":
                                    //rs命令必须指定开始时间
                                    if ("".equals(cmdMatcher.group(2))) {
                                        return;
                                    }
                                case "make":
                                    mpService.reserveLobby(cqMsg);
                                    break;
                                case "invite":
                                    if ("".equals(cmdMatcher.group(2))) {
                                        return;
                                    }
                                    mpService.invitePlayer(cqMsg);
                                    break;
                                case "list":
                                    mpService.listLobby(cqMsg);
                                    break;
                                case "abort":
                                    mpService.abortReserve(cqMsg);
                                    break;
                                case "join":
                                    mpService.joinLobby(cqMsg);
                                    break;
                                case "add":
                                    mpService.addMap(cqMsg);
                                    break;
                                case "del":
                                    mpService.delMap(cqMsg);
                                    break;
                                case "listmap":
                                    mpService.listMap(cqMsg);
                                    break;
                                case "help":
                                    mpService.help(cqMsg);
                                default:
                                    break;
                            }
                            break;
                        default:
                            Matcher recentQianeseMatcher = RegularPattern.QIANESE_RECENT.matcher(cmdMatcher.group(1).toLowerCase(Locale.CHINA));
                            if (recentQianeseMatcher.find()) {
                                cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                cqService.recent(cqMsg);
                            }
                            switch (cmdMatcher.group(1).toLowerCase(Locale.CHINA)) {
                                case "stat":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.DAY, ParameterEnum.MODE});
                                    cqService.statUserInfo(cqMsg);
                                    break;
                                case "statu":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USER_ID});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.DAY, ParameterEnum.MODE});
                                    cqService.statUserInfo(cqMsg);
                                    break;
                                case "statme":
                                case  "statsme":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.DAY, ParameterEnum.MODE});
                                    cqService.statUserInfo(cqMsg);
                                    break;
                                case "bp":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.NUM, ParameterEnum.MODE});
                                    cqService.pretreatmentParameterForBPCommand(cqMsg);
                                    if (cqMsg.getArgument() == null) {
                                        //在BP的参数错误情况下，会直接返回到Controller中，此时没有Argument会触发NPE
                                        return;
                                    }
                                    if (cqMsg.getArgument().getNum() != null) {
                                        //如果有指定#n
                                        //如果Service内部调用，AOP无法拦截
                                        cqService.printSpecifiedBP(cqMsg);
                                    } else {
                                        cqService.printBP(cqMsg);
                                    }
                                    break;
                                case "bpu":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USER_ID});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.NUM, ParameterEnum.MODE});
                                    cqService.pretreatmentParameterForBPCommand(cqMsg);
                                    if (cqMsg.getArgument() == null) {
                                        //在BP的参数错误情况下，会直接返回到Controller中，此时没有Argument会触发NPE
                                        return;
                                    }
                                    if (cqMsg.getArgument().getNum() != null) {
                                        //如果有指定#n
                                        //如果Service内部调用，AOP无法拦截
                                        cqService.printSpecifiedBP(cqMsg);
                                    } else {
                                        cqService.printBP(cqMsg);
                                    }
                                    break;
                                case "bps":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.NUM, ParameterEnum.MODE});
                                    cqService.pretreatmentParameterForBPCommand(cqMsg);
                                    if (cqMsg.getArgument() == null) {
                                        //在BP的参数错误情况下，会直接返回到Controller中，此时没有Argument会触发NPE
                                        return;
                                    }
                                    if (cqMsg.getArgument().getNum() != null) {
                                        //如果有指定#n
                                        //如果Service内部调用，AOP无法拦截
                                        cqService.printSpecifiedBP(cqMsg);
                                    } else {
                                        cqService.printBP(cqMsg);
                                    }
                                    break;
                                case "bpus":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USER_ID});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.NUM, ParameterEnum.MODE});
                                    cqService.pretreatmentParameterForBPCommand(cqMsg);
                                    if (cqMsg.getArgument() == null) {
                                        //在BP的参数错误情况下，会直接返回到Controller中，此时没有Argument会触发NPE
                                        return;
                                    }
                                    if (cqMsg.getArgument().getNum() != null) {
                                        //如果有指定#n
                                        //如果Service内部调用，AOP无法拦截
                                        cqService.printSpecifiedBP(cqMsg);
                                    } else {
                                        cqService.printBP(cqMsg);
                                    }
                                    break;
                                case "bpme":
                                case "mybp":
                                case "mybps":
                                case "bpmes":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.NUM, ParameterEnum.MODE});
                                    cqService.pretreatmentParameterForBPCommand(cqMsg);
                                    if (cqMsg.getArgument() == null) {
                                        //在BP的参数错误情况下，会直接返回到Controller中，此时没有Argument会触发NPE
                                        return;
                                    }
                                    if (cqMsg.getArgument().getNum() != null) {
                                        //如果有指定#n
                                        //如果Service内部调用，AOP无法拦截
                                        cqService.printSpecifiedBP(cqMsg);
                                    } else {
                                        cqService.printBP(cqMsg);
                                    }
                                    break;
                                case "setid":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqService.setId(cqMsg);
                                    break;
                                case "rs":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqService.recent(cqMsg);
                                    break;
                                case "help":
                                    cqService.help(cqMsg);
                                    break;
                                case "sleep":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.HOUR});
                                    cqService.sleep(cqMsg);
                                    break;
                                case "add":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME, ParameterEnum.QQ});
                                    cqService.chartMemberCmd(cqMsg);
                                    break;
                                case "del":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqService.chartMemberCmd(cqMsg);
                                    break;
                                case "me":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.SEARCH_PARAM});
                                    cqService.myScore(cqMsg);
                                    break;
                                case "search":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.SEARCH_PARAM});
                                    cqService.search(cqMsg);
                                    break;
                                case "costme":
                                case "mycost":
                                    cqService.cost(cqMsg);
                                    break;
                                case "cost":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqService.cost(cqMsg);
                                    break;
                                case "pr":
                                case "prs":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqService.recentPassed(cqMsg);
                                    break;
                                case "bns":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.USERNAME});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqService.getBonusPP(cqMsg);
                                    break;
                                case "mybns":
                                case "bnsme":
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqService.getBonusPP(cqMsg);
                                    break;
                                case "mode":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.MODE});
                                    cqService.setMode(cqMsg);
                                    break;
                                case "roll":
                                    cqService.roll(cqMsg);
                                    break;
                                case "time":
                                    cqService.time(cqMsg);
                                    break;
                                case "addmap":
                                    analyzeService.addTargetMap(cqMsg);
                                    break;
                                case "delmap":
                                    analyzeService.delTargetMap(cqMsg);
                                    break;
                                case "delallmap":
                                    analyzeService.delAllTargetMap(cqMsg);
                                    break;
                                case "listmap":
                                    analyzeService.listTargetMap(cqMsg);
                                    break;
                                case "adduser":
                                    analyzeService.addTargetUser(cqMsg);
                                    break;
                                case "deluser":
                                    analyzeService.delTargetUser(cqMsg);
                                    break;
                                case "delalluser":
                                    analyzeService.delAllTargetUser(cqMsg);
                                    break;
                                case "listuser":
                                    analyzeService.listTargetUser(cqMsg);
                                    break;
                                case "upduser":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.SHADOWSOCKS_USER, ParameterEnum.SHADOWSOCKS_NUMBER});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.SHADOWSOCKS_CONFIRM});
                                    shadowSocksCmdService.service(cqMsg);
                                    break;
                                case "getcode":
                                    cqMsg.setRequired(new ParameterEnum[]{ParameterEnum.SHADOWSOCKS_NUMBER});
                                    cqMsg.setOptional(new ParameterEnum[]{ParameterEnum.SHADOWSOCKS_COUNT});
                                    shadowSocksCmdService.service(cqMsg);
                                    break;
                                default:
                                    // 转交给黄花菜
//                                    boolean hasDayLily = false;
//                                    if ("group".equals(cqMsg.getMessageType())) {
//                                        CqResponse<List<QQInfo>> cqResponse;
//                                        cqResponse = cqManager.getGroupMembers(cqMsg.getGroupId());
//                                        for (QQInfo qqInfo : cqResponse.getData()) {
//                                            if (Long.valueOf(2181697779L).equals(qqInfo.getUserId())) {
//                                                hasDayLily = true;
//                                            }
//                                        }
//                                    }
//                                    if (!hasDayLily) {
                                    dayLilyManager.sendMsg(cqMsg);
//                                    }
                                    break;

                            }
                            break;
                    }

                }
                break;
            case "event":
                if ("group_increase".equals(cqMsg.getEvent())) {
                    //新增人口
                    cqService.welcomeNewsPaper(cqMsg);
                }
                if ("group_decrease".equals(cqMsg.getEvent())) {
                    //有人退群
                    cqService.seeYouNextTime(cqMsg);
                }
                if ("group_admin".equals(cqMsg.getEvent())) {
                    //群管变动
                    smokeUtil.loadGroupAdmins();
                }
                break;
            case "request":
                //只有是加群请求的时候才进入
                if ("group".equals(cqMsg.getRequestType()) && "invite".equals(cqMsg.getSubType())) {
                    cqAdminService.stashInviteRequest(cqMsg);
                }
                break;
            default:
                logger.error("传入无法识别的Request，可能是HTTP API插件已经更新");
        }
    }

    @PostConstruct
    private void notifyInitComplete() {
        CqMsg cqMsg = new CqMsg();
        cqMsg.setMessageType("private");
        cqMsg.setUserId(1335734657L);
        cqMsg.setMessage("初始化完成，欢迎使用");
        cqManager.sendMsg(cqMsg);
    }
}
