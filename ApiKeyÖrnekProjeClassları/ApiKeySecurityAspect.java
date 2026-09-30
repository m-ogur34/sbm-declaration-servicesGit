package com.allianz.common.collection.aspect;

import com.allianz.common.collection.exception.AppException;
import com.allianz.common.collection.exception.BankCollectionException;
import com.allianz.common.collection.model.enums.bank_collection.BankCollectionWebServiceMessage;
import com.allianz.common.collection.service.ApiKeySecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ApiKeySecurityAspect {

    private final ApiKeySecurityConfig apiKeySecurityConfig;
    private final ApiKeySecurityService apiKeySecurityService;
    private final HttpServletRequest httpServletRequest;

    
    @Around("@annotation(com.allianz.common.collection.aspect.ApiKeySecurityAnnotation)")
    public Object checkApiKey(ProceedingJoinPoint joinPoint) throws Throwable {
        String apiKey = httpServletRequest.getHeader("X-ApiKey");
        String path = httpServletRequest.getRequestURI();
        String ip = httpServletRequest.getRemoteAddr();
        String host = extractHost(httpServletRequest);

        if (apiKey == null || apiKey.isEmpty()) {
            throw new AppException("API Key bilgisi eksik.");
        }

        List<ApiKeySecurityConfig.ApiKeyConfig> apiKeyConfigs = apiKeySecurityConfig.getApiKeys();
        if (apiKeyConfigs == null || apiKeyConfigs.isEmpty()) {
            throw new AppException("API Key security konfigürasyonu bulunamadı.");
        }

        log.info("ip: {}, path: {}, host: {}", ip, path, host);

        ApiKeySecurityConfig.ApiKeyConfig matchedApiKeyConfig = apiKeyConfigs.stream()
                .filter(config -> config.getApiKey() != null)
                .filter(config -> MessageDigest.isEqual(
                        apiKey.getBytes(StandardCharsets.UTF_8),
                        config.getApiKey().getBytes(StandardCharsets.UTF_8)))
                .findFirst()
                .orElseThrow(() -> new BankCollectionException(BankCollectionWebServiceMessage.AUTHENTICATION_ERROR));

        apiKeySecurityService.checkApiKeyPermittedUrls(matchedApiKeyConfig.getPermittedUrls(), path);
        apiKeySecurityService.checkApiKeyAllowedIps(matchedApiKeyConfig.getAllowedIps(), ip);

        return joinPoint.proceed();
    }

    private String extractHost(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.trim().isEmpty()) {
            int commaIndex = forwardedHost.indexOf(',');
            return (commaIndex > -1 ? forwardedHost.substring(0, commaIndex) : forwardedHost).trim();
        }

        return request.getHeader("Host");
    }
}
