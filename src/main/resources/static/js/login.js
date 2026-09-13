$(function () {
    // 이미 로그인돼 있으면 계좌 관리로
    $.ajax({ url: "/api/admin/me", method: "GET" })
        .done(function () { window.location.href = "/admin/account"; });

    $("#loginForm").on("submit", function (event) {
        event.preventDefault();
        var loginId = $("#loginId").val();
        var password = $("#password").val();
        $("#loginError").hide();

        $.ajax({
            url: "/api/auth/admin/login",
            method: "POST",
            contentType: "application/json",
            data: JSON.stringify({ loginId: loginId, password: password })
        })
            .done(function () {
                // 토큰은 서버가 쿠키로 설정 — 저장 불필요
                window.location.href = "/admin/account";
            })
            .fail(function (xhr) {
                var message = "로그인에 실패했습니다.";
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    message = xhr.responseJSON.message;
                }
                $("#loginError").text(message).show();
            });
    });
});
