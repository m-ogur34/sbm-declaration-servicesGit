package tr.com.allianz.ysv.services.security;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * İşaretli controller (sınıf) ya da metot yalnız geçerli {@code X-ApiKey} başlığıyla çağrılabilir.
 * Kontrol {@link ApiKeySecurityInterceptor}'da yapılır.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, METHOD})
public @interface ApiKeySecurityAnnotation {
}
