import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ReportManager {

    // God method handling everything
    public void execute(HttpServletRequest request, HttpServletResponse response) {
        String action = request.getParameter("action");

        try {
            response.setContentType("text/html");
            Connection conn = DriverManager.getConnection("jdbc:mysql://localhost/db", "root", "password");

            if ("view".equals(action)) {
                String type = request.getParameter("type");
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM reports WHERE type = ?");
                ps.setString(1, type);
                ResultSet rs = ps.executeQuery();

                response.getWriter().println("<html><body><h1>Report Type: " + type + "</h1>");
                while (rs.next()) {
                    response.getWriter().println("<p>" + rs.getString("title") + "</p>");
                }
                response.getWriter().println("</body></html>");

            } else if ("delete".equals(action)) {
                String reportId = request.getParameter("report_id");
                PreparedStatement ps = conn.prepareStatement("DELETE FROM reports WHERE id = ?");
                ps.setString(1, reportId);
                ps.executeUpdate();
                response.getWriter().println("{\"deleted\": true}");
            }

            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}