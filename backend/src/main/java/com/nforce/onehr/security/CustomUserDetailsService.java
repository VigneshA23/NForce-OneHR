package com.nforce.onehr.security;

import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
        // re-login required. The only authorities are module:ACTION:SCOPE strings computed from the
        // roles' explicit grants: no ROLE_<code> authority is issued (nothing checks one — see
        // NoRawRoleChecksTest), so a role name can never authorize anything by itself.
        List<GrantedAuthority> authorities = new ArrayList<>(effectivePermissionService.computeAuthorities(user.getRoles()));

        boolean disabled = !user.isActive() || user.getDeletedAt() != null;
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getTokenVersion(), user.getTokenVersionReason(), disabled, authorities);
    }
}
