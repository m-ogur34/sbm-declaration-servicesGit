package tr.com.allianz.ysv.services.config;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tr.com.allianz.ysv.services.dto.request.RequestContext;

/** Controller metotlarındaki {@link RequestContext} parametresini header'lardan doldurur. */
public class RequestContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return RequestContext.class.equals(parameter.getParameterType());
    }

    @Override
    public RequestContext resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
        return RequestContext.of(
                webRequest.getHeader(RequestContext.USER_HEADER),
                webRequest.getHeader(RequestContext.REQUESTER_ID_TYPE_HEADER),
                webRequest.getHeader(RequestContext.REQUESTER_ID_NO_HEADER));
    }
}
