package com.holysilent.jproxy.servlet;

import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Created by silent on 2017/1/6.
 */
public class HttpProxyServlet extends HttpServlet{

    private Properties p = new Properties();
    private String marathonURL = "http://10.124.128.87:8080";
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void init(ServletConfig config) throws ServletException {
        try {
            InputStream in = getClass().getClassLoader().getResourceAsStream("init.properties");
            p.load(in);
            this.marathonURL = p.getProperty("marathon-ui") == null ? "" : p.getProperty("marathon-ui");
            logger.info("param {} : {}", new Object[]{"marathon-ui", this.marathonURL});
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if(req.getSession().getAttribute("httpclient")==null){
            HttpClient httpClient = HttpClients.createDefault();
            req.getSession().setAttribute("httpclient",httpClient);
        }

        super.service(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (StringUtils.isBlank(this.marathonURL)) {
            resp.setStatus(404);
            resp.getWriter().write("sorry, marathon ui configured error.");
            resp.getWriter().flush();
            resp.getWriter().close();
            return;
        }

        String reqURIStr = req.getRequestURI();
        String queryStr = req.getQueryString();
        String targetURL = this.marathonURL + reqURIStr + (StringUtils.isBlank(queryStr) ? "" : "?"+queryStr);
        logger.info("targetURL : {}", targetURL);
        HttpGet get = new HttpGet(targetURL);

        CloseableHttpResponse httpResponse = null;
        HttpClientContext context = HttpClientContext.create();
        try {
            CloseableHttpClient httpClient = (CloseableHttpClient)req.getSession().getAttribute("httpclient");
            get.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            httpResponse = httpClient.execute(get,context);
            Header[] headers = httpResponse.getAllHeaders();
            for(Header header:headers){
                if (header.getName().trim().toLowerCase().equals("x-frame-options") || header.getName().trim().toLowerCase().equals("set-cookie") ) {
                    continue;
                }
                resp.setHeader(header.getName(),header.getValue());
            }
            if(httpResponse.getStatusLine().getStatusCode()==302){
                Header[] locations = httpResponse.getHeaders("Location");
                if(locations.length>0) {
                    String location = locations[0].getValue();
                    String redirct = null;
                    if (location.startsWith("http://")) {
                        URL re = new URL(location);
                        if (re.getQuery() == null || re.getQuery().equals("")) {
                            redirct = re.getPath();
                        } else {
                            redirct = re.getPath() + "?" + re.getQuery();
                        }
                    } else {
                        redirct = location;
                    }
                    resp.setHeader("Location",redirct);
                }
            }
            setCookieStore(httpResponse,resp);
            writeResponse(httpResponse,resp);

        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            try {
                if(httpResponse!=null) {
                    httpResponse.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    }

    private void writeResponse(CloseableHttpResponse httpResponse,HttpServletResponse resp) throws IOException {

        resp.setStatus(httpResponse.getStatusLine().getStatusCode());
        HttpEntity entity = httpResponse.getEntity();
        if(entity==null){
            return;
        }
        InputStream is =entity.getContent();
        byte[] byteArr = new byte[1024];
        //读取的字节数
        int readCount = 0;
        readCount = is.read(byteArr);

        OutputStream outputStream = resp.getOutputStream();
        //如果已到达文件末尾，则返回-1
        while (readCount != -1) {
            outputStream.write(byteArr, 0, readCount);
            readCount = is.read(byteArr);
        }
        outputStream.flush();
        outputStream.close();
        is.close();

    }

    private void setCookieStore(CloseableHttpResponse httpResponse,HttpServletResponse resp) {
        Header[] headers = httpResponse.getHeaders("Set-Cookie");
        if(headers.length > 0) {
            for(Header header: headers) {
                if(header.getName().trim().toLowerCase().equals("X-Frame-Options")) {
                    continue;
                }
                String[] cookies = header.getValue().split(";");
                String[] strs = cookies[0].split("=");
                if (strs.length == 2) {
                    Cookie cookie = new Cookie(strs[0], strs[1]);
                    cookie.setPath("/");
                    resp.addCookie(cookie);
                }
            }
        }
    }
}
