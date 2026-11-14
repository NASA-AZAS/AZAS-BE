package azas.global.logging;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RequestLoggingConfiguration {

    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingRegistration(RequestLoggingFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(RequestLoggingFilter.ORDER);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
