package com.xinchao.nettywebsocketdemo.netty;

import com.xinchao.nettywebsocketdemo.chat.ChannelManage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryAttribute;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Sharable
public class ServerHandler extends ChannelHandlerAdapter {

    //用于websocket握手的处理类
    private WebSocketServerHandshaker handshaker;

    private static final String WEB_SOCKET_URL = "/websocket";

    //客户端与服务端创建连接的时候调用
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ChannelManage.group.add(ctx.channel());
        System.out.println("客户端与服务端连接开启，客户端remoteAddress：" + ctx.channel().remoteAddress());
    }

    //客户端与服务端断开连接的时候调用
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ChannelManage.group.remove(ctx.channel());
        System.out.println("客户端与服务端连接关闭...");
    }

    //服务端接收客户端发送过来的数据结束之后调用
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    /**
     * 获取数据
     * @param ctx 上下文
     * @param msg 获取的数据
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
         /* 传统的HTTP接入（采用http处理方式）
         * 第一次握手请求消息由HTTP协议承载，所以它是一个HTTP消息，
         * 握手成功后，数据就直接从 TCP 通道传输，与 HTTP 无关了。
         * 执行handleHttpRequest方法来处理WebSocket握手请求。
         */

        // FullHttpRequest是完整的 HTTP请求，协议头和Form数据是在一起的，不用分开读
        if (msg instanceof FullHttpRequest) {
            // websocket连接请求
            handleHttpRequest(ctx, (FullHttpRequest)msg);
        }
        /*
         *  WebSocket接入（采用socket处理方式）
         *  提交请求消息给服务端，
         *  WebSocketServerHandler接收到的是已经解码后的WebSocketFrame消息。
         */
        else if (msg instanceof WebSocketFrame) {
            // websocket业务处理
            handleWebSocketRequest(ctx, (WebSocketFrame)msg);
        }
        /*
         * Websocket的数据传输是frame形式传输的，比如会将一条消息分为几个frame，按照先后顺序传输出去。这样做会有几个好处：
         *
         * 1）大数据的传输可以分片传输，不用考虑到数据大小导致的长度标志位不足够的情况。
         *
         * 2）和http的chunk一样，可以边生成数据边传递消息，即提高传输效率。
         */
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        ctx.close();
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Http解码失败，向服务器指定传输的协议为Upgrade：websocket
        if (!req.decoderResult().isSuccess() || (!"websocket".equals(req.headers().get("Upgrade")))) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }
        // 握手相应处理,创建websocket握手的工厂类，
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(WEB_SOCKET_URL, null, false);
        // 根据工厂类和HTTP请求创建握手类
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            // 不支持websocket
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            // 通过它构造握手响应消息返回给客户端
            handshaker.handshake(ctx.channel(), req);
        }
    }

    private void handleWebSocketRequest(ChannelHandlerContext ctx, WebSocketFrame req) throws Exception {
        //判断是否是关闭websocket的指令
        if (req instanceof CloseWebSocketFrame) {
            // 关闭websocket连接
            handshaker.close(ctx.channel(), (CloseWebSocketFrame)req.retain());
            return;
        }
        //判断是否是ping消息
        if (req instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(req.content().retain()));
            return;
        }
        if (!(req instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException("当前只支持文本消息，不支持二进制消息");
        }
        if (ctx == null || this.handshaker == null || ctx.isRemoved()) {
            throw new Exception("尚未握手成功，无法向客户端发送WebSocket消息");
        }

        // 返回应答消息
        String requestMsg = ((TextWebSocketFrame) req).text();
        System.out.println("收到客户端" + ctx.channel().remoteAddress() + "的消息==》" + requestMsg);
        String[] array = requestMsg.split(",");
        // 先判断通道管理器中是否存在该通道，没有则添加进去
        if (!ChannelManage.hasChannel(array[0])) {
            ChannelManage.userIdAndChannelMap.put(array[0], ctx.channel());
        }

        if (array[0].length() != 0 && array[1].length() != 0) {
            ChannelManage.send(array[0], array[1], array[2], ctx.channel());
        } else if (array[0].length() != 0 && array[1].length() == 0) {
            //如果没有指定接收者表示群发array.length() = 2
            System.out.println("用户" + array[0] + "群发了一条消息：" + array[2]);
            ChannelManage.group.writeAndFlush(new TextWebSocketFrame("用户" + array[0] + "群发了一条消息：" + array[2]));
        } else {
            //如果没有指定发送者与接收者表示向服务端发送array.length() = 1
            System.out.println("服务端接收用户" + ctx.channel().remoteAddress() + "消息，不再发送出去");
            ctx.writeAndFlush(new TextWebSocketFrame("你向服务端发送了消息==》" + array[2]));
        }
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        // BAD_REQUEST(400) 客户端请求错误返回的应答消息
        if (res.status().code() != 200) {
            // 将返回的状态码放入缓存中，Unpooled没有使用缓存池
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            HttpHeaderUtil.setContentLength(res, res.content().readableBytes());
        }
        // 发送应答消息
        ChannelFuture cf = ctx.channel().writeAndFlush(res);
        // 非法连接直接关闭连接
        if (!HttpHeaderUtil.isKeepAlive(req) || res.status().code() != 200) {
            cf.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 解析GET、POST请求参数
     * @return 包含所有请求参数的键值对, 如果没有参数, 则返回空Map
     *
     * @throws IOException
     */
    public Map<String, String> parse(FullHttpRequest fullReq) throws IOException {

        HttpMethod method = fullReq.method();

        Map<String, String> parmMap = new HashMap<>();

        if (HttpMethod.GET == method) {
            // 是GET请求
            QueryStringDecoder decoder = new QueryStringDecoder(fullReq.uri());
            decoder.parameters().entrySet().forEach( entry -> {
                // entry.getValue()是一个List, 只取第一个元素
                parmMap.put(entry.getKey(), entry.getValue().get(0));
            });
        } else if (HttpMethod.POST == method) {
            HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(
                    new DefaultHttpDataFactory(false), fullReq);
            List<InterfaceHttpData> postData = decoder.getBodyHttpDatas();
            for(InterfaceHttpData data:postData){
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                    MemoryAttribute attribute = (MemoryAttribute) data;
                    parmMap.put(attribute.getName(), attribute.getValue());
                }
            }
        } else {
            // 不支持其它方法
            System.out.println("不支持其他方法提交的参数");
        }

        return parmMap;
    }

}
