package com.allianz.common.collection.service;


import com.allianz.common.collection.exception.BankCollectionException;
import com.allianz.common.collection.model.enums.bank_collection.BankCollectionWebServiceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeySecurityService {

    public void checkApiKeyPermittedUrls(List<String> permittedUrls, String requestUri) {
        if (permittedUrls == null || permittedUrls.isEmpty()) {
            return;
        }

        boolean isPermitted = permittedUrls.stream()
                .anyMatch(requestUri::contains);

        if (!isPermitted) {
            log.warn("ApiKey Security -> API Key does not have access to URI: {}", requestUri);
            throw new BankCollectionException(BankCollectionWebServiceMessage.AUTHENTICATION_ERROR);
        }
    }

    public void checkApiKeyAllowedIps(List<String> allowedIps, String remoteIp) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            return;
        }

        boolean isAllowed = allowedIps.stream()
                .anyMatch(ip -> ip.equals(remoteIp));

        if (!isAllowed) {
            log.warn("ApiKey Security -> IP address {} is not allowed", remoteIp);
            throw new BankCollectionException(BankCollectionWebServiceMessage.AUTHENTICATION_ERROR);
        }
    }
}
