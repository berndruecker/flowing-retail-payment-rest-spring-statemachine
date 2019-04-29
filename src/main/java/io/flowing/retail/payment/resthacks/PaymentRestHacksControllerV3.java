package io.flowing.retail.payment.resthacks;

import static org.springframework.web.bind.annotation.RequestMethod.PUT;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachine;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;

/**
 * Step3: Use Camunda state machine for long-running retry
 */
@RestController
@EnableStateMachine
public class PaymentRestHacksControllerV3 {

  public enum States {
      START,
      RETRIEVE_PAYMENT,
      WAIT_FOR_PAYMENT_RETRY,
      DONE
  }
  
  public enum  Events {
      STARTED,
      PAYMENT_RECEIVED,
      PAYMENT_UNAVAILABLE
  }

  @Configuration
//  @EnableStateMachine
  @EnableStateMachineFactory
  public static class StateMachineConfig extends EnumStateMachineConfigurerAdapter <States, Events> {
  
      @Override
      public void configure(StateMachineConfigurationConfigurer<States, Events> config) throws Exception {
          config.withConfiguration().autoStartup(true);
      }
  
      @Override
      public void configure(StateMachineStateConfigurer<States, Events> states) throws Exception {
          states.withStates() //
                .initial(States.START)
                .state(States.RETRIEVE_PAYMENT, new CallStripeAction())
                .state(States.WAIT_FOR_PAYMENT_RETRY)
                .end(States.DONE);
      }
  
      @Override
      public void configure(StateMachineTransitionConfigurer<States, Events> transitions) throws Exception {
          // @formatter:off
          transitions.withExternal()  
                .source(States.START)
                .target(States.RETRIEVE_PAYMENT)
                .event(Events.STARTED)
                .and()
                .withExternal()
                .source(States.RETRIEVE_PAYMENT)
                .target(States.DONE)
                .event(Events.PAYMENT_RECEIVED)
                .and()
                .withExternal()
                .source(States.RETRIEVE_PAYMENT)
                .target(States.WAIT_FOR_PAYMENT_RETRY)
                .event(Events.PAYMENT_UNAVAILABLE)
                .and()
                .withExternal()
                .source(States.WAIT_FOR_PAYMENT_RETRY)
                .target(States.RETRIEVE_PAYMENT)
                .timer(5000l); // 5 seconds
       // @formatter:on
      }
  }

  @Autowired
  private StateMachineFactory<States, Events> stateMachineFactory;
  
  private final Map<String, StateMachine<States, Events>> stateMachines = new HashMap<>();

  @Component("stripeAdap  ter")
  public static class CallStripeAction implements Action<States, Events> {

    @Autowired
    private RestTemplate rest;
    private String stripeChargeUrl = "http://localhost:8099/charge";

    @Override
    public void execute(StateContext<States, Events> ctx) {
      try {
        CreateChargeRequest request = new CreateChargeRequest();
        request.amount = (long) ctx.getExtendedState().getVariables().get("amount");
  
        CreateChargeResponse response = new HystrixCommand<CreateChargeResponse>(HystrixCommandGroupKey.Factory.asKey("stripe")) {
          protected CreateChargeResponse run() throws Exception {
              return rest.postForObject( //
                stripeChargeUrl, //
                request, //
                CreateChargeResponse.class);
          }
        }.execute();
        
        ctx.getExtendedState().getVariables().put("paymentTransactionId", response.transactionId);
        ctx.getStateMachine().sendEvent(Events.PAYMENT_RECEIVED);
     } catch (Exception ex) {
       System.out.println(ex.getMessage());
//       ex.printStackTrace();
       ctx.getStateMachine().sendEvent(Events.PAYMENT_UNAVAILABLE);
     }
    }
  }

  @RequestMapping(path = "/api/payment/v3", method = PUT)
  public String retrievePayment(String retrievePaymentPayload, HttpServletResponse response) throws Exception {
    String traceId = UUID.randomUUID().toString();
    String customerId = "0815"; // get somehow from retrievePaymentPayload
    long amount = 15; // get somehow from retrievePaymentPayload

    chargeCreditCard(customerId, amount);
    
    response.setStatus(HttpServletResponse.SC_ACCEPTED);    
    return "{\"status\":\"pending\", \"traceId\": \"" + traceId + "\"}";
  }

  public void chargeCreditCard(String customerId, long remainingAmount) {
    String uuid = UUID.randomUUID().toString();
    
    StateMachine<States,Events> stateMachine = stateMachineFactory.getStateMachine(uuid);
    stateMachines.put(uuid, stateMachine); // TODO remove when finished!
    
    stateMachine.getExtendedState().getVariables().put("amount", remainingAmount);
    stateMachine.sendEvent(Events.STARTED);
    // TODO: Think about persistence! 
  }
  
  public static class CreateChargeRequest {
    public long amount;
  }

  public static class CreateChargeResponse {
    public String transactionId;
  }

}