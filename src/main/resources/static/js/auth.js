/**
 * 인증 공용 유틸 (jQuery 기반) — HttpOnly 쿠키 전환 버전.
 * 토큰은 서버가 HttpOnly 쿠키로 관리. 브라우저가 자동 전송.
 * localStorage 토큰 관련 코드 전부 제거.
 */
var MARIA = window.MARIA || {};

MARIA.auth = (function ($) {

    var _admin = null;
    var _authPromise = null;

    // GET /api/admin/me 호출로 로그인 여부 확인.
    // ajax() 래퍼를 사용해 access token 만료 시 refresh를 먼저 시도한 뒤 재호출.
    // refresh도 실패하면 logout()이 /login으로 리다이렉트. Promise를 반환하므로 .done()에서 후속 처리.
    // 이미 진행 중이거나 완료된 요청이 있으면 같은 promise 반환 — 중복 호출 방지.
    function requireAuth() {
        if (_authPromise) return _authPromise;
        var deferred = $.Deferred();
        _authPromise = deferred.promise();
        ajax({ url: "/api/admin/me", method: "GET" })
            .done(function (res) {
                _admin = res.data;
                deferred.resolve(res.data);
            })
            .fail(function (xhr) {
                _authPromise = null;
                deferred.reject(xhr);
            });
        return _authPromise;
    }

    function logout() {
        $.ajax({ url: "/api/auth/admin/logout", method: "POST" })
            .always(function () { window.location.href = "/login"; });
    }

    // Authorization 헤더 없음 — 브라우저가 쿠키 자동 전송.
    // 401이면 refresh 시도(refresh_token 쿠키 자동 전송) 후 원 요청 재시도.
    function ajax(options) {
        var deferred = $.Deferred();

        $.ajax(options)
            .done(function (data, textStatus, jqXHR) {
                deferred.resolve(data, textStatus, jqXHR);
            })
            .fail(function (xhr) {
                if (xhr.status !== 401) {
                    deferred.reject(xhr);
                    return;
                }
                refreshAccessToken()
                    .done(function () {
                        $.ajax(options)
                            .done(function (data, textStatus, jqXHR) {
                                deferred.resolve(data, textStatus, jqXHR);
                            })
                            .fail(function (retryXhr) {
                                if (retryXhr.status === 401) { logout(); }
                                deferred.reject(retryXhr);
                            });
                    })
                    .fail(function () {
                        logout();
                        deferred.reject(xhr);
                    });
            });

        return deferred.promise();
    }

    var refreshInFlight = null;
    function refreshAccessToken() {
        if (refreshInFlight) return refreshInFlight;
        refreshInFlight = $.ajax({
            url: "/api/auth/admin/refresh",
            method: "POST"
            // body 없음 — refresh_token 쿠키 자동 전송
        }).always(function () { refreshInFlight = null; });
        return refreshInFlight;
    }

    function currentAdmin() { return _admin; }

    return { requireAuth: requireAuth, currentAdmin: currentAdmin, logout: logout, ajax: ajax };
})(jQuery);
