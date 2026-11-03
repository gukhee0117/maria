/* 관리자 화면 공통 UI 도우미 (A-8, 처리 화면용).
   layout.js 는 공통 파일이라 건드리지 않고, 성공 토스트만 여기서 같은 토스트 컨테이너에 만든다.
   (오류 토스트는 layout.js 의 MARIA.ui.showError 를 그대로 쓴다.) */
var MARIA = window.MARIA || {};
window.MARIA = MARIA;
MARIA.ui = MARIA.ui || {};

MARIA.ui.showSuccess = function (message) {
    var $container = $("#toastContainer");
    var maxToastCount = 3;
    while ($container.children(".toast").length >= maxToastCount) {
        $container.children(".toast").first().remove();
    }
    var $toast = $("<div>", { "class": "toast is-ok", text: message || "처리했어요" });
    $container.append($toast);
    requestAnimationFrame(function () { $toast.addClass("is-visible"); });
    setTimeout(function () {
        $toast.removeClass("is-visible");
        setTimeout(function () { $toast.remove(); }, 200);
    }, 3500);
};
