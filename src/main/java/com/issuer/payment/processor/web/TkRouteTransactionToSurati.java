package com.issuer.payment.processor.web;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

import javax.json.JsonObject;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.hamcrest.Matchers;
import org.takes.Request;
import org.takes.Response;
import org.takes.Take;
import org.takes.facets.flash.RsFlash;
import org.takes.facets.forward.RsForward;
import org.takes.rq.RqGreedy;
import org.takes.rq.form.RqFormSmart;

import com.jcabi.http.request.JdkRequest;
import com.jcabi.http.response.JsonResponse;
import com.jcabi.http.response.RestResponse;
import com.jcabi.log.Logger;

public final class TkRouteTransactionToSurati implements Take {

	final String suratiUrl;
	
	public TkRouteTransactionToSurati(final String suratiUrl) {
		this.suratiUrl = suratiUrl;
	}
	
	@Override
	public Response act(Request req) throws Exception {
		
		final RqFormSmart rqForm = new RqFormSmart(new RqGreedy(req));
		
		final Iterable<String> fieldKeys = rqForm.names();
		
		String rrn = "", pan = "";
		
		final StringBuilder body = new StringBuilder("");
		for (String key : fieldKeys) {
			if(rqForm.single(key, "").equals(""))
				continue;
			
			if(!body.toString().equals("")) {
				body.append("&");
			}
			
			if(key.equals("2"))
				pan = rqForm.single(key);
			
			if(key.equals("37"))
				rrn = rqForm.single(key);
			
			body.append(String.format("%s=%s", key, rqForm.single(key))); 
		}
		
		final LocalDateTime now = LocalDateTime.now();
		
		if(rqForm.single("7", "").equals("")) {
			body.append(String.format("&7=%s", now.format(DateTimeFormatter.ofPattern("MMddHHmmss"))));
		}
		
		if(rqForm.single("12", "").equals("")) {
			body.append(String.format("&12=%s", now.format(DateTimeFormatter.ofPattern("HHmmss"))));
		}
		
		if(rqForm.single("13", "").equals("")) {
			body.append(String.format("&13=%s", now.format(DateTimeFormatter.ofPattern("MMdd"))));
		}
		
		if(rqForm.single("73", "").equals("")) {
			body.append(String.format("&73=%s", now.format(DateTimeFormatter.ofPattern("yyMMdd"))));
		}
		
		try {
			JsonObject json = routeTransaction(
				rrn, 
				pan, 
				suratiUrl, 
				this, 
				body.toString()
			);
			
			return new RsForward(
					new RsFlash(
						String.format("Transaction successfully routed to Surati with response %s (%s) !", json.getString("response_message"), json.getString("response_code")),
						Level.INFO
					),
					String.format("/transaction/routing/output?rrn=%s&pan=%s", rrn, pan)
				);
		} catch (IllegalArgumentException e) {
			return new RsForward(
					new RsFlash(
						String.format("Error : %s", e.getLocalizedMessage()),
						Level.WARNING
					),
					String.format("/transaction/routing/output?rrn=%s&pan=%s", rrn, pan)
				);
		}
	}
	
	public static JsonObject routeTransaction(
			final String rrn, 
			final String pan, 
			final String suratiUrl, 
			final Take take, 
			final String body
	)  throws Exception {
		
		try {
			Logger.info(take, "Transaction (rrn=%s, pan=%s) : Started --------------------------------", rrn, pan);
			Logger.info(take, "Transaction (rrn=%s, pan=%s) : %s routes transaction to Surati", rrn, pan, Main.APP_NAME);
			
			Logger.info(take, "Transaction (rrn=%s, pan=%s) : Surati controls transaction.", rrn, pan);
			
			final RestResponse response = new JdkRequest(suratiUrl)
											.uri().path("/api/transaction/control").back()
											.method(com.jcabi.http.Request.POST)
											.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
											.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
											.fetch(new ByteArrayInputStream(body.getBytes()))
											.as(RestResponse.class)
											.assertStatus(Matchers.isOneOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_BAD_REQUEST));
			
			final JsonObject rsJson = response.as(JsonResponse.class)
											  .json()
											  .read()
											  .asJsonObject();
			
			
			
			if(response.status() == HttpURLConnection.HTTP_BAD_REQUEST) {
				throw new IllegalArgumentException(String.format("Error : %s", rsJson.getString("message")));
			} else {
				Logger.info(take, "Transaction (rrn=%s, pan=%s) : Surati successfully finished with message -> %s (%s)", rrn, pan, rsJson.getString("response_message"), rsJson.getString("response_code"));
				
				Thread.sleep(100);
				
				Logger.info(take, "Transaction (rrn=%s, pan=%s) : %s notifies Surati of the end of transaction.", rrn, pan, Main.APP_NAME);
				
				final String body1 = String.format("rrn=%s&pan=%s", rrn, pan);
				
				final RestResponse response1 = new JdkRequest(suratiUrl)
						.uri().path("/api/transaction/finalize").back()
						.method(com.jcabi.http.Request.POST)
						.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
						.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
						.fetch(new ByteArrayInputStream(body1.getBytes()))
						.as(RestResponse.class)
						.assertStatus(Matchers.isOneOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_BAD_REQUEST));

				if(response1.status() == HttpURLConnection.HTTP_BAD_REQUEST) {
					final JsonObject rsJson1 = response1.as(JsonResponse.class)
							  .json()
							  .read()
							  .asJsonObject();
					throw new IllegalArgumentException(rsJson1.getString("message"));
				}

				return rsJson;
			}		
		}  finally {
			Logger.info(take, "Transaction (rrn=%s, pan=%s) : Finished --------------------------------", rrn, pan);
		}
	}

}
