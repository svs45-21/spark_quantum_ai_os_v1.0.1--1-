/* Program 9(a) : Bubble Sort Method
   Compiler     : Turbo C++ 3.0                                    */
#include<iostream.h>
#include<conio.h>

void main()
{
  int n, i, arr[50], j, temp;
  clrscr();
  cout<<"Enter the Size (max. 50): ";
  cin>>n;
  if(n>50) n=50;
  cout<<"Enter "<<n<<" Numbers: ";
  for(i=0;i<n;i++)
    cin>>arr[i];
  cout<<"\nSorting the Array using Bubble Sort Technique..\n";
  for(i=0;i<(n-1);i++)
  {
    for(j=0;j<(n-i-1);j++)
    {
      if(arr[j]>arr[j+1])
      {
        temp=arr[j];
        arr[j]=arr[j+1];
        arr[j+1]=temp;
      }
    }
  }
  cout<<"\nArray Sorted Successfully!\n";
  cout<<"\nThe New Array is: \n";
  for(i=0;i<n;i++)
    cout<<arr[i]<<"  ";
  cout<<endl;
  getch();
}
