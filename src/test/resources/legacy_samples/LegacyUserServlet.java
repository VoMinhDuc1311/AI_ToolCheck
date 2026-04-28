import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class LegacyUserServlet extends HttpServlet {

    // Configured in web.xml as /UserServlet
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String userId = request.getParameter("id");
        String includeAddress = request.getParameter("include_address");

        response.setContentType("application/json");
        PrintWriter out = response.getWriter();

        if (userId != null) {
            out.print("{\"status\": \"success\", \"user_id\": \"" + userId + "\"}");
        } else {
            out.print("{\"status\": \"error\", \"msg\": \"Missing id parameter\"}");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String user = request.getParameter("username");
        String mail = request.getParameter("email");

        // Save to DB logic here...

        response.setStatus(201);
        response.getWriter().write("User " + user + " created successfully.");
    }
}