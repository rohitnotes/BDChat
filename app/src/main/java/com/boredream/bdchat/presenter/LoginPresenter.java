package com.boredream.bdchat.presenter;

import android.os.SystemClock;

import com.boredream.bdchat.utils.IMUserProvider;
import com.boredream.bdcodehelper.base.UserInfoKeeper;
import com.boredream.bdcodehelper.entity.CloudResponse;
import com.boredream.bdcodehelper.entity.User;
import com.boredream.bdcodehelper.net.ErrorConstants;
import com.boredream.bdcodehelper.net.HttpRequest;
import com.boredream.bdcodehelper.net.RxComposer;
import com.boredream.bdcodehelper.utils.LogUtils;
import com.boredream.bdcodehelper.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Function;
import io.reactivex.observers.DisposableObserver;
import io.rong.imkit.RongIM;
import io.rong.imlib.RongIMClient;

public class LoginPresenter implements LoginContract.Presenter {

    private static final int SPLASH_DUR_MIN_TIME = 1500;
    private static final int SPLASH_DUR_MAX_TIME = 5000;

    private final LoginContract.View view;

    public LoginPresenter(LoginContract.View view) {
        this.view = view;
    }

    @Override
    public void autoLogin(String sessionToken) {
        Map<String, String> request = new HashMap<>();
        request.put("sessionToken", sessionToken);
        // TODO: 2017/7/5 user rxjava
        final long startTime = SystemClock.elapsedRealtime();
        decObservable(HttpRequest.getSingleton()
                .getApiService()
                .imLogin(request)
                .flatMap(new Function<CloudResponse<User>, ObservableSource<CloudResponse<User>>>() {
                    @Override
                    public ObservableSource<CloudResponse<User>> apply(@NonNull CloudResponse<User> response) throws Exception {
                        // 接口返回后，凑够最短时间跳转
                        long requestTime = SystemClock.elapsedRealtime() - startTime;
                        long delayTime = requestTime < SPLASH_DUR_MIN_TIME
                                ? SPLASH_DUR_MIN_TIME - requestTime : 0;
                        LogUtils.showLog("autoLogin delay = " + delayTime);
                        return Observable.just(response).delay(delayTime, TimeUnit.MILLISECONDS);
                    }
                })
                .timeout(SPLASH_DUR_MAX_TIME, TimeUnit.MILLISECONDS));
    }

    @Override
    public void login(String username, String password) {
        if (StringUtils.isEmpty(username)) {
            view.showTip("用户名不能为空");
            return;
        }

        if (StringUtils.isEmpty(password)) {
            view.showTip("密码不能为空");
            return;
        }

        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);
        decObservable(HttpRequest.getSingleton()
                .getApiService()
                .imLogin(request));
    }

    private void decObservable(Observable<CloudResponse<User>> observable) {
        observable.compose(RxComposer.<User>handleCloudResponse())
                .flatMap(new Function<User, ObservableSource<User>>() {
                    @Override
                    public ObservableSource<User> apply(@NonNull User user) throws Exception {
                        return getImLoginObservable(user);
                    }
                })
                .compose(RxComposer.<User>schedulers())
                .subscribe(new DisposableObserver<User>() {
                    @Override
                    public void onNext(@NonNull User user) {
                        // 保存登录用户数据以及token信息
                        UserInfoKeeper.getInstance().setCurrentUser(user);
                        // 保存自动登录使用的信息
                        UserInfoKeeper.getInstance().saveSessionToken(user.getSessionToken());
                        // 同步通讯录
                        IMUserProvider.syncAllContacts();

                        view.loginSuccess(user);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        view.loginError(ErrorConstants.parseHttpErrorInfo(e));
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private Observable<User> getImLoginObservable(final User user) {
        return Observable.create(new ObservableOnSubscribe<User>() {
            @Override
            public void subscribe(@NonNull final ObservableEmitter<User> e) throws Exception {
                // 融云回调转换成RxJava回调
                RongIM.connect(user.getImToken(), new RongIMClient.ConnectCallback() {

                    /**
                     * Token 错误。可以从下面两点检查
                     * 1.  Token 是否过期，如果过期您需要向 App Server 重新请求一个新的 Token
                     * 2.  token 对应的 appKey 和工程里设置的 appKey 是否一致
                     */
                    @Override
                    public void onTokenIncorrect() {
                        e.onError(new Throwable("im token error"));
                    }

                    /**
                     * 连接融云成功
                     *
                     * @param userid 当前 token 对应的用户 id
                     */
                    @Override
                    public void onSuccess(String userid) {
                        LogUtils.showLog("getImLoginObservable: get imToken success");
                        e.onNext(user);
                        e.onComplete();
                    }

                    /**
                     * 连接融云失败
                     *
                     * @param errorCode 错误码，可到官网 查看错误码对应的注释
                     */
                    @Override
                    public void onError(RongIMClient.ErrorCode errorCode) {
                        e.onError(new Throwable(errorCode.toString()));
                    }
                });
            }
        });
    }
}
