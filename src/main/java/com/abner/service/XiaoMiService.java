package com.abner.service;


import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.abner.annotation.Async;
import com.abner.annotation.Resource;
import com.abner.annotation.Retry2;
import com.abner.annotation.Service;
import com.abner.annotation.Stop;
import com.abner.annotation.Timing;
import com.abner.enums.TimingType;
import com.abner.manage.FilePathManage;
import com.abner.manage.MyThreadPool;
import com.abner.manage.StatusManage;
import com.abner.manage.mi.Config;
import com.abner.pojo.mi.Cookie;
import com.abner.pojo.mi.GoodsInfo;
import com.abner.pojo.mi.User;
import com.abner.utils.FileUtil;
import com.abner.utils.JsonUtil;

/**
 * 小米抢购服务
 * @author liwei
 * @date: 2018年6月11日 下午1:48:31
 *
 */
@Service
public class XiaoMiService {
	
	private static  Logger logger = LoggerFactory.getLogger(XiaoMiService.class);
	
	@Resource
	private HttpService httpService;
	
	@Resource
	private ParseHtmlService parseHtmlService;
	
	private ScheduledFuture<?> buy;
	
	private ScheduledFuture<?> stop;
	
	
	public boolean islogin(){
		if(!FileUtil.isFile(FilePathManage.userConfig)){
			return false;
		}
		String miString = FileUtil.readFileToString(FilePathManage.userConfig);
		if(miString==null||miString.length()==0){
			return false;
		}
		User oldUser = JsonUtil.toBean(miString, User.class);
		if(oldUser==null){
			return false;
		}
		if(!oldUser.equals(Config.user)){
			return false;
		}
		if(oldUser.getCookies()==null||oldUser.getCookies().size()==0){
			return false;
		}
		String result = httpService.execute(FilePathManage.checkLoginStatusJs,"");
		if(result.equals("true")){
			Config.user.setCookies(oldUser.getCookies());
			return true;
		}
		return false;

	}
	
	/**
	 * 保持登录状态
	 */
	@Timing(initialDelay = 0, period = 10, type = TimingType.FIXED_RATE, unit = TimeUnit.MINUTES)
	public void keeplogin() {
		if(!islogin()){
			StatusManage.isLogin = false;
			login();
			StatusManage.isLogin = true;
		}else{
			logger.info("用户:{} 已登录。",Config.user.getUserName());
			StatusManage.isLogin = true;
		}
	}
	
	@Retry2(success = "ok")
	public String login() {
		long start = System.currentTimeMillis();
		FileUtil.writeToFile(JsonUtil.toString(Config.user), FilePathManage.userConfig);
		String result = httpService.execute(FilePathManage.loginJs,"");
		if(result.length()==0||result.equals("cache")){
			logger.error("用户:{} 登录失败,时间:{}ms,正准备重试。。。建议清空缓存。",Config.user.getUserName(),System.currentTimeMillis()-start);
			return "fail";
		}else if(result.equals("pwd")){
			logger.error("用户名或密码错误！");
			stop("用户名或密码错误！");
			return "ok";
		}else{
			List<Cookie> cookies = JsonUtil.toList(result, Cookie.class);
			Config.user.setCookies(cookies);
			FileUtil.writeToFile(JsonUtil.toString(Config.user), FilePathManage.userConfig);
			logger.info("用户:{} 登录成功,时间:{}ms",Config.user.getUserName(),System.currentTimeMillis()-start);
			return "ok";
		}
		
	}


	/**
	 * httpClient执行购买
	 * @param buyUrl
	 * @param cookies
	 */
	@Timing(initialDelay = 0, period = 400, type = TimingType.FIXED_RATE, unit = TimeUnit.MILLISECONDS)
	public void buyGoodsTask() {
		if(StatusManage.isLogin){
			buy(Config.goodsInfo.randomBuyUrl(),Config.user.getCookies());
		}
	}
	
	@Async(50)
	public void buy(String buyUrl, List<Cookie> cookies){
		long start = System.currentTimeMillis();
		String re = httpService.getByCookies(buyUrl, cookies);
		if(re!=null){
			if(parseHtmlService.isBuySuccess(re)){
				stop("恭喜！抢购成功,赶紧去购物车付款吧!");
				return;
			}
			logger.info("提交成功({}),看人品咯！{}ms,{}",Config.submitCount.addAndGet(1),System.currentTimeMillis()-start,buyUrl);
		}
	}
	
	public void start(){
		//购买
		buy = MyThreadPool.schedule(()->{
			logger.info("开始抢购。。。");
			buyGoodsTask();
			
		}, Config.customRule.getBuyTime()-System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		//抢购时间截止
		stop = MyThreadPool.schedule(()->{
			stop("抢购时间截止，停止抢购");
		}, Config.customRule.getEndTime()-System.currentTimeMillis(), TimeUnit.MILLISECONDS);

	}
	@Stop(methods = { "buyGoodsTask" ,"keeplogin"})
	public void stop(String msg) {
		logger.info(msg);
		StatusManage.endMsg = msg;
		if(buy!=null){
			buy.cancel(false);//停止 购买定时器
		}
		
		if(stop!=null){
			stop.cancel(false);//停止 截止时间的定时器
		}

		StatusManage.isEnd = true;
	}
	@Async
	public void parseUrl(String url) {
		try{
			if(!url.startsWith(GoodsInfo.BASE_INFOURL)){
				StatusManage.endMsg = "链接地址错误";
				return ;
			}
			String result = httpService.execute(FilePathManage.buyGoodsJs,url);
			logger.info(result);
			if(result.length()==0){
				StatusManage.endMsg = "链接地址分析失败";
				return ;
			}
			GoodsInfo goodsInfo = JsonUtil.toBean(result, GoodsInfo.class);
			if(goodsInfo==null||goodsInfo.getGoodsIds()==null||goodsInfo.getGoodsIds().size()==0){
				StatusManage.endMsg = "链接地址分析失败";
				return ;
			}
			goodsInfo.setUrl(url);
			Config.goodsInfo = goodsInfo;
			StatusManage.isParse = true;
			StatusManage.endMsg = "";
		}catch (Exception e) {
			StatusManage.endMsg = "链接地址分析失败";
		}finally {
			GoodsInfo.ParseCount.incrementAndGet();
		}
	}
	
}
