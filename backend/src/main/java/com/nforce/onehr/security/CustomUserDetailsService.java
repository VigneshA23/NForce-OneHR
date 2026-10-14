package com.nforce.onehr.security;

import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final EffectivePermissionService effectivePermissionService;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        // Roles are re-resolved from the DB on every request (this method runs fresh per
        // request, not cached off the JWT — see JwtAuthenticationFilter), so a permission
        // change (same as a role change) takes effect on the user's very next request, no
        // re-login required. ROLE_* is kept alongside the new module:ACTION:SCOPE authorities
        // during the Phase 3 migration, while hasRole/hasAnyRole call sites still exist.
        List<SimpleGrantedAuthority> roleAuthorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getCode()))
                .collect(Collectors.toList());
        List<SimpleGrantedAuthority> permissionAuthorities = effectivePermissionService.computeAuthorities(user.getRoles());

        List<GrantedAuthority> authorities = new ArrayList<>(roleAuthorities.size() + permissionAuthorities.size());
        authorities.addAll(roleAuthorities);
        authorities.addAll(permissionAuthorities);

        boolean disabled = !user.isActive() || user.getDeletedAt() != null;
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getTokenVersion(), user.getTokenVersionReason(), disabled, authorities);
    }
}
