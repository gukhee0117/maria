package com.app.maria.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/admin/target-products")
    public String targetProducts(Model model) {
        model.addAttribute("activePath", "/admin/target-products");
        return "target-products";
    }

    @GetMapping("/admin/sell-orders")
    public String sellOrders(Model model) {
        model.addAttribute("activePath", "/admin/sell-orders");
        return "sell-order";
    }

    @GetMapping("/admin/account")
    public String account(Model model) {
        model.addAttribute("activePath", "/admin/account");
        return "account-management";
    }

    @GetMapping("/admin/audit-log")
    public String audit(Model model) {
        model.addAttribute("activePath", "/admin/audit-log");
        return "audit-log";
    }

    @GetMapping("/admin/settlement")
    public String settlement(Model model) {
        model.addAttribute("activePath", "/admin/settlement");
        return "settlement";
    }

    @GetMapping("/admin/inbound")
    public String inbound(Model model) {
        model.addAttribute("activePath", "/admin/inbound");
        return "inbound-management";
    }

    @GetMapping("/admin/admin-users")
    public String admin(Model model) {
        model.addAttribute("activePath", "/admin/admin-users");
        return "admin-users";
    }

    @GetMapping("/admin/domestic-investment")
    public String domesticInvestment(Model model) {
        model.addAttribute("activePath", "/admin/domestic-investment");
        return "domestic-investment";
    }

    @GetMapping("/admin/account-closures")
    public String accountClosures(Model model) {
        model.addAttribute("activePath", "/admin/account-closures");
        return "account-closures";
    }

    @GetMapping("/admin/withdrawals")
    public String withdrawals(Model model) {
        model.addAttribute("activePath", "/admin/withdrawals");
        return "withdrawals";
    }

    @GetMapping("/admin/tax")
    public String tax(Model model) {
        model.addAttribute("activePath", "/admin/tax");
        return "tax-calculation";
    }

    @GetMapping("/admin/fund-flow")
    public String fundFlow(Model model) {
        model.addAttribute("activePath", "/admin/fund-flow");
        return "fund-flow";
    }
}
