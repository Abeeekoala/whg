module filter #(
    parameter TAPS  = 49, // Number of filter taps
    parameter SHIFT = 8   // <<-- TRY 8 INSTEAD OF 16
)(
    input  wire               clk,
    input  wire signed [31:0] x_in,
    output reg  signed [31:0] x_out
);

    reg signed [15:0] coeffs [0:TAPS-1];

    initial begin
        coeffs[0]  =   1;   coeffs[1]  =   2;   coeffs[2]  =  -1;  coeffs[3]  =  -2;
        coeffs[4]  =   1;   coeffs[5]  =   0;   coeffs[6]  =  -3;  coeffs[7]  =   1;
        coeffs[8]  =   1;   coeffs[9]  =  -4;   coeffs[10] =   1;  coeffs[11] =   3;
        coeffs[12] =  -5;   coeffs[13] =   0;   coeffs[14] =   6;  coeffs[15] =  -6;
        coeffs[16] =  -3;   coeffs[17] =  10;   coeffs[18] =  -7;  coeffs[19] =  -8;
        coeffs[20] =  19;   coeffs[21] =  -7;   coeffs[22] = -31;  coeffs[23] =  74;
        coeffs[24] = 165;   coeffs[25] =  74;   coeffs[26] = -31;  coeffs[27] =  -7;
        coeffs[28] =  19;   coeffs[29] =  -8;   coeffs[30] =  -7;  coeffs[31] =  10;
        coeffs[32] =  -3;   coeffs[33] =  -6;   coeffs[34] =   6;  coeffs[35] =   0;
        coeffs[36] =  -5;   coeffs[37] =   3;   coeffs[38] =   1;  coeffs[39] =  -4;
        coeffs[40] =   1;   coeffs[41] =   1;   coeffs[42] =  -3;  coeffs[43] =   0;
        coeffs[44] =   1;   coeffs[45] =  -2;   coeffs[46] =  -1;  coeffs[47] =   2;
        coeffs[48] =   1;
    end

    reg signed [31:0] delay_line [0:TAPS-1];
    integer i;
    reg signed [47:0] acc;  // 48-bit accumulator

    always @(posedge clk) begin
        // Shift delay line
        for (i = TAPS-1; i > 0; i = i - 1) begin
            delay_line[i] <= delay_line[i-1];
        end
        delay_line[0] <= x_in;

        // Multiply-accumulate
        acc = 0;
        for (i = 0; i < TAPS; i = i + 1) begin
            acc = acc + (delay_line[i] * coeffs[i]);
        end

        // Discard SHIFT bits: keep [47 : SHIFT]
        x_out <= acc[47:SHIFT];
    end

endmodule
