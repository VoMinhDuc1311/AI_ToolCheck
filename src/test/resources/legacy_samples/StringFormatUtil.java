import java.util.regex.Pattern;

public class StringFormatUtil {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Chuyển đổi chuỗi thành Snake Case
     * Ví dụ: "helloWorld" -> "hello_world"
     */
    public static String toSnakeCase(String camelCase) {
        if (camelCase == null)
            return null;
        return camelCase.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    /**
     * Kiểm tra tính hợp lệ của Email
     * 
     * @param email chuỗi đầu vào
     * @return true nếu hợp lệ
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    // Một vài method nghiệp vụ tính toán nội bộ
    public double calculateDiscount(double price, int customerLoyaltyPoints) {
        if (customerLoyaltyPoints > 1000)
            return price * 0.8;
        if (customerLoyaltyPoints > 500)
            return price * 0.9;
        return price;
    }
}