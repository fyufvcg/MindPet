package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import util.RailwayApiUtil;
import util.RailwayApiUtil.RailwayAccount;

@Component
public class BookTicketTool {

    @Tool(description = "Book a train ticket immediately. Requires user's 12306 cookie and passenger info to be saved first. Checks ticket availability and submits the order to 12306 (order goes to unpaid status, user pays within 30 min on 12306 APP).")
    public String bookTicket(
            @ToolParam(description = "Departure station name (e.g. \"杭州东\")") String from,
            @ToolParam(description = "Arrival station name (e.g. \"北京南\")") String to,
            @ToolParam(description = "Travel date YYYY-MM-DD") String date,
            @ToolParam(description = "Train number (e.g. \"G144\")") String trainNo,
            @ToolParam(description = "Seat type: 商务座, 一等座, 二等座, 软卧, 硬卧, 硬座. Default 二等座.") String seat) {

        if (from == null || to == null || date == null || trainNo == null) {
            return "Missing required info. Need: from, to, date, train_no, seat.\n"
                + "Example: book_ticket(from=\"杭州东\", to=\"北京南\", date=\"2026-07-25\", train_no=\"G144\", seat=\"二等座\")";
        }
        if (seat == null || seat.isEmpty()) seat = "二等座";

        // 1. Load account
        RailwayAccount account;
        try {
            account = RailwayApiUtil.loadAccount();
        } catch (Exception e) {
            return "Failed to read account file: " + e.getMessage();
        }
        if (account == null || account.cookie() == null) {
            return "No 12306 account info saved. Please send your account details in this format:\n\n"
                + "/account\ncookie=RAIL_EXPIRATION=xxx; RAIL_DEVICEID=yyy;...\npassenger_name=姓名\npassenger_id=身份证号\npassenger_phone=手机号\n\n"
                + "Get the cookie from your browser after logging in to 12306 web.";
        }
        if (account.name() == null || account.id() == null) {
            return "Passenger info incomplete. Name and ID are required. Please re-send:\n\n"
                + "/account\npassenger_name=姓名\npassenger_id=身份证号\npassenger_phone=手机号";
        }

        // 2. Validate cookie
        if (!RailwayApiUtil.validateCookie(account.cookie())) {
            return "12306 cookie has expired. Please re-send a fresh cookie from your browser."
                + "\n\n/account\ncookie=...new cookie...";
        }

        // 3. Submit order
        String result = RailwayApiUtil.submitOrder(from, to, date, trainNo, seat, account);
        return result;
    }
}
