package com.investory.controller;

import com.investory.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RegisterController {

    @Autowired private AuthService authService;

    @GetMapping("/register")
    public String registerGet() {
        return "register";
    }

    @PostMapping("/register")
    public String registerPost(@RequestParam String username,
                               @RequestParam String password,
                               @RequestParam(required = false) String email,
                               Model model) {
        String error = authService.register(username, password, email);
        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("username", username);
            model.addAttribute("email", email);
            return "register";
        }
        return "redirect:/login?registered=1";
    }
}
