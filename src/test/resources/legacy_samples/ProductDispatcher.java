import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

public class ProductDispatcher {

    public void handleProductRequests(HttpServletRequest req, HttpServletResponse res) throws Exception {
        String uri = req.getRequestURI();
        String method = req.getMethod();
        PrintWriter out = res.getWriter();

        if ("/api/products/list".equalsIgnoreCase(uri) && "GET".equalsIgnoreCase(method)) {
            String category = req.getParameter("category");
            out.println("Returning list of products for category: " + category);

        } else if (uri.endsWith("/products/add")) {
            // Assume POST if it's add
            if (!"POST".equals(method)) {
                res.sendError(405, "Method not allowed");
                return;
            }
            String pName = req.getParameter("name");
            String pPrice = req.getParameter("price");
            // insert to db
            out.println("Product " + pName + " added with price " + pPrice);

        } else {
            res.sendError(404, "Unknown product route");
        }
    }
}