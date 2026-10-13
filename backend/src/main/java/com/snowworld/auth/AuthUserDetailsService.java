package com.snowworld.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AuthUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public AuthUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserMapper.UserRow row = userMapper.findByUsername(username);
        if (row == null) {
            throw new UsernameNotFoundException(username);
        }
        return new AuthUser(row.getId(), row.getUsername(), row.getDisplayName(), row.getPasswordHash(),
                "ACTIVE".equals(row.getStatus()), userMapper.findRoles(row.getId()));
    }
}
