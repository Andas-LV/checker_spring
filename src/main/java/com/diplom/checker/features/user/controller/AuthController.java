package com.diplom.checker.features.user.controller;

import com.diplom.checker.features.user.model.User;
import com.diplom.checker.features.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

@RestController
public class AuthController {

    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;

    @CrossOrigin(origins = {"*"})
    @PostMapping("/register")
    public User register(@RequestBody Map<String,String> r, HttpSession session) {
        if (users.findByEmail(r.get("email")).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email taken");
        }
        User u = new User();
        u.setEmail(r.get("email"));
        u.setUsername(r.get("username"));
        u.setPassword(encoder.encode(r.get("password")));
        u = users.save(u);
        session.setAttribute("userId", u.getId());
        return u;
    }

    @CrossOrigin(origins = {"*"})
    @PostMapping("/login")
    public User login(@RequestBody Map<String,String> r, HttpSession session) {
        User u = users.findByEmail(r.get("email"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!encoder.matches(r.get("password"), u.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        session.setAttribute("userId", u.getId());
        return u;
    }

    @CrossOrigin(origins = {"*"})
    @GetMapping("/user/me")
    public User me(HttpSession session) {
        Object id = session.getAttribute("userId");
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return users.findById((Long)id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
