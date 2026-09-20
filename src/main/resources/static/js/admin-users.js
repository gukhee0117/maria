$(function () {
    var ROLES = ["VIEWER", "REVIEWER", "SETTLEMENT"];
    var ROLE_LABEL = {
        VIEWER: "조회전용",
        REVIEWER: "심사담당",
        SETTLEMENT: "정산담당",
        ADMIN: "최고관리자"
    };
    var admins = [];
    var isAdmin = false;

    function escapeHtml(value) {
        return $("<div>").text(value == null ? "" : value).html();
    }

    function roleLabel(role) {
        return ROLE_LABEL[role] || role || "-";
    }

    function roleClass(role) {
        return (role || "").toLowerCase();
    }

    function showError(message) {
        MARIA.ui.showError(message);
    }

    function errorMessage(xhr, fallback) {
        return (xhr.responseJSON && xhr.responseJSON.message) || fallback;
    }

    function handleRequestFailure(xhr, fallback, onFailure) {
        if (xhr.status === 401) {
            return;
        }
        if (onFailure) {
            onFailure();
        }
        showError(errorMessage(xhr, fallback));
    }

    function roleOptions(currentRole) {
        return ROLES.map(function (role) {
            var selected = role === currentRole ? " selected" : "";
            return '<option value="' + role + '"' + selected + '>' + roleLabel(role) + "</option>";
        }).join("");
    }

    function renderAdmins() {
        var $body = $("#adminUserListBody").empty();
        $("#adminUserCount").text(admins.length + "명");

        if (!admins.length) {
            $body.append('<tr><td colspan="4" class="admin-users-empty">등록된 관리자가 없습니다.</td></tr>');
            return;
        }

        admins.forEach(function (admin) {
            var roleCell = admin.role === "ADMIN"
                ? '<td class="admin-users-role-fixed">담당자에게 문의</td>'
                : '<td><form class="admin-users-role-form" data-admin-id="' + admin.adminId + '">' +
                  '<select class="admin-users-role-select"' + (isAdmin ? "" : " disabled") + ">" + roleOptions(admin.role) + "</select>" +
                  '<button type="submit" class="btn btn-primary admin-users-role-save"' + (isAdmin ? "" : " disabled") + ">저장</button>" +
                  "</form></td>";

            $body.append(
                '<tr data-admin-id="' + admin.adminId + '">' +
                '<td class="admin-users-name">' + escapeHtml(admin.name || "-") + "</td>" +
                "<td>" + escapeHtml(admin.loginId || "-") + "</td>" +
                '<td><span class="admin-users-role-badge ' + roleClass(admin.role) + '">' + escapeHtml(roleLabel(admin.role)) + "</span></td>" +
                roleCell +
                "</tr>"
            );
        });
    }

    function loadAdmins() {
        $("#adminUserListBody").html('<tr><td colspan="4" class="admin-users-loading">불러오는 중...</td></tr>');
        MARIA.auth.ajax({ url: "/api/admin/admins", method: "GET" })
            .done(function (res) {
                admins = res.data || [];
                renderAdmins();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "관리자 목록을 불러오지 못했습니다.", function () { $("#adminUserListBody").empty(); });
            });
    }

    $(document).on("submit", ".admin-users-role-form", function (event) {
        event.preventDefault();
        if (!isAdmin) {
            return;
        }
        var adminId = $(this).data("admin-id");
        var role = $(this).find(".admin-users-role-select").val();
        MARIA.auth.ajax({
            url: "/api/admin/admins/" + adminId + "/role",
            method: "PATCH",
            contentType: "application/json",
            data: JSON.stringify({ role: role })
        })
            .done(function () {
                loadAdmins();
            })
            .fail(function (xhr) {
                handleRequestFailure(xhr, "역할 변경에 실패했습니다.");
            });
    });

    MARIA.auth.requireAuth().done(function (admin) {
        isAdmin = !!(admin && admin.role === "ADMIN");
        loadAdmins();
    });
});
